"""Command-line entry point:  python -m harness.run_experiments --scenario mixed --repeats 5

Runs every (scenario x system x timeout x retries x threshold) cell ``--repeats`` times and writes
``<results-dir>/<config_id>/run_<k>.{csv,json,requests.csv}``. See README.md, "Running the experiments".
"""
from __future__ import annotations

import argparse
import asyncio
import json
import platform
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import List

from . import sweep
from .runner import RunParams, is_done, run_one
from .scenarios import DEFAULT_FILE, load_scenarios
from .services import make_controller

ROOT = Path(__file__).resolve().parent.parent


def _csv_ints(s: str) -> List[int]:
    return [int(x) for x in s.split(",") if x.strip()]


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="run_experiments",
        description="Tail-latency-aware circuit-breaking experiments: scenario x system x config-grid x repeats.")
    p.add_argument("--scenario", default="all",
                   help="comma-separated scenario names (baseline,delay,errors,mixed,coldstart) or 'all'")
    p.add_argument("--systems", default=",".join(sweep.SYSTEMS), help="subset of none,static,adaptive")
    p.add_argument("--repeats", type=int, default=None, help="runs per configuration (default 5; 1 with --quick)")
    p.add_argument("--quick", action="store_true",
                   help="smoke-test preset: reduced grid, 45 s runs, 1 repeat (explicit flags still override)")
    p.add_argument("--timeouts", type=_csv_ints, default=None, help="override per-attempt timeouts in ms")
    p.add_argument("--retries", type=_csv_ints, default=None, help="override retry counts")
    p.add_argument("--thresholds", type=_csv_ints, default=None, help="override failure thresholds")
    p.add_argument("--users", type=int, default=290)
    p.add_argument("--ramp-rate", type=float, default=100.0, help="users spawned per second")
    p.add_argument("--think-min", type=float, default=1.0)
    p.add_argument("--think-max", type=float, default=3.0)
    p.add_argument("--duration", type=float, default=None, help="measured seconds per run (default 300; 45 with --quick)")
    p.add_argument("--warmup", type=float, default=None, help="warm-up seconds under load (default 15; 10 with --quick)")
    p.add_argument("--interval", type=float, default=5.0, help="metrics export interval in seconds")
    p.add_argument("--seed", type=int, default=42, help="base seed; repeat k uses seed+k")
    p.add_argument("--mode", choices=["local", "docker", "attach"], default="local")
    p.add_argument("--results-dir", default=str(ROOT / "results"))
    p.add_argument("--scenarios-file", default=str(DEFAULT_FILE))
    p.add_argument("--open-wait-ms", type=int, default=10_000)
    p.add_argument("--half-open-probes", type=int, default=3)
    p.add_argument("--retry-policy", choices=["ALL_FAILURES", "ISTIO_GATEWAY_ERROR"], default="ALL_FAILURES")
    p.add_argument("--adaptive-json", default="{}", help='adaptive-breaker tuning, e.g. \'{"riseSlopeRelPerSec":0.1}\'')
    p.add_argument("--no-resume", action="store_true", help="re-run configurations whose result files already exist")
    p.add_argument("--limit", type=int, default=None, help="execute only the first N runs of the plan")
    p.add_argument("--cooldown", type=float, default=3.0, help="seconds to idle between runs")
    p.add_argument("--cpu-source", choices=["auto", "oshi", "jmx"], default="auto",
                   help="CPU sampler source (local mode). auto = OSHI if it agrees with the JVM counter, else JVM. "
                        "Use 'jmx' on hosts where OSHI is unreliable (e.g. broken Windows perf counters) so that all "
                        "services use the same source")
    p.add_argument("--build", action="store_true", help="run 'mvn clean install -DskipTests' first (local mode)")
    p.add_argument("--dry-run", action="store_true", help="print the plan and the time estimate, run nothing")
    return p


def resolve(args):
    quick = args.quick
    args.repeats = args.repeats if args.repeats is not None else (1 if quick else 5)
    args.duration = args.duration if args.duration is not None else (45.0 if quick else 300.0)
    args.warmup = args.warmup if args.warmup is not None else (10.0 if quick else 15.0)
    args.timeouts = args.timeouts or list(sweep.QUICK_TIMEOUTS_MS if quick else sweep.FULL_TIMEOUTS_MS)
    args.retries = args.retries if args.retries is not None else list(sweep.QUICK_RETRIES if quick else sweep.FULL_RETRIES)
    args.thresholds = args.thresholds or list(sweep.QUICK_THRESHOLDS if quick else sweep.FULL_THRESHOLDS)
    return args


def environment_info(args) -> dict:
    def sh(cmd):
        try:
            return subprocess.run(cmd, capture_output=True, text=True, timeout=20).stderr.strip().splitlines()[0]
        except Exception:
            return None
    import os
    return {
        "createdEpoch": time.time(), "args": vars(args), "python": sys.version.split()[0],
        "platform": platform.platform(), "machine": platform.machine(), "logicalCpus": os.cpu_count(),
        "java": sh([shutil.which("java") or "java", "-version"]),
        "note": "Numbers are only comparable between runs made on the same machine under the same load.",
    }


async def main_async(args) -> int:
    scenarios = load_scenarios(Path(args.scenarios_file))
    names = list(scenarios) if args.scenario == "all" else [s.strip() for s in args.scenario.split(",")]
    for n in names:
        if n not in scenarios:
            print(f"unknown scenario '{n}'; available: {', '.join(scenarios)}", file=sys.stderr)
            return 2
    systems = [s.strip() for s in args.systems.split(",")]
    for s in systems:
        if s not in sweep.SYSTEMS:
            print(f"unknown system '{s}'", file=sys.stderr)
            return 2

    plan = sweep.plan_runs(names, systems, args.timeouts, args.retries, args.thresholds, args.repeats, args.seed)
    results_dir = Path(args.results_dir)
    todo = [r for r in plan if args.no_resume or not is_done(results_dir, r)]
    if args.limit is not None:
        todo = todo[:args.limit]

    per_run_s = args.duration + args.warmup + 12
    print(f"plan: {len(plan)} runs ({len(plan) - len([r for r in plan if args.no_resume or not is_done(results_dir, r)])} "
          f"already done), executing {len(todo)}")
    print(f"      scenarios={names} systems={systems} timeouts={args.timeouts} retries={args.retries} "
          f"thresholds={args.thresholds} repeats={args.repeats}")
    print(f"      each run ~{per_run_s:.0f} s  ->  estimated {len(todo) * per_run_s / 3600:.1f} h "
          f"({len(todo)} runs x {per_run_s:.0f} s)")
    if args.dry_run:
        for r in todo[:10]:
            print("   ", r.config_id, f"run_{r.repeat}", f"seed={r.seed}")
        if len(todo) > 10:
            print(f"    ... {len(todo) - 10} more")
        return 0
    if not todo:
        print("nothing to do (all result files exist; use --no-resume to re-run)")
        return 0
    if len(todo) * per_run_s > 4 * 3600:
        print("WARNING: this is a long run. It resumes where it stopped if you re-run the same command; "
              "use --quick, --limit, --duration or a narrower grid for smoke tests.")

    params = RunParams(users=args.users, ramp_rate=args.ramp_rate, think_min=args.think_min, think_max=args.think_max,
                       duration_s=args.duration, warmup_s=args.warmup, interval_s=args.interval,
                       open_wait_ms=args.open_wait_ms, half_open_probes=args.half_open_probes,
                       retry_policy=args.retry_policy, adaptive=json.loads(args.adaptive_json))

    results_dir.mkdir(parents=True, exist_ok=True)
    meta_dir = results_dir / "_meta"
    meta_dir.mkdir(exist_ok=True)
    (meta_dir / "environment.json").write_text(json.dumps(environment_info(args), indent=1, default=str), encoding="utf-8")
    shutil.copy(args.scenarios_file, meta_dir / "scenarios.yaml")

    if args.build and args.mode == "local":
        print("building ...")
        subprocess.run(["mvn", "-q", "-B", "clean", "install", "-DskipTests"], cwd=str(ROOT), check=True,
                       shell=(sys.platform == "win32"))

    ctl = make_controller(args.mode, ROOT, ROOT / "logs", cpu_source=args.cpu_source)
    if any(scenarios[n].events for n in names) and not ctl.can_restart:
        print("scenarios with restart events need --mode local or docker", file=sys.stderr)
        return 2

    failures = 0
    await ctl.start_all()
    try:
        for i, spec in enumerate(todo, 1):
            t = time.time()
            print(f"[{i}/{len(todo)}] {spec.config_id} run_{spec.repeat} (seed {spec.seed}) ...", flush=True)
            try:
                res = await run_one(spec, scenarios[spec.scenario], params, ctl, results_dir, first_run=(i == 1))
                e = res["e2e"]
                print(f"      done in {time.time() - t:.0f}s: n={e['count']} err={e['errorRate']:.1%} "
                      f"avg={e['avg']:.0f}ms p95={e['p95']:.0f}ms p99={e['p99']:.0f}ms", flush=True)
            except Exception as ex:  # keep going: a failed run leaves no files and is retried on resume
                failures += 1
                print(f"      FAILED: {ex!r}", flush=True)
                try:
                    await ctl.ensure_healthy()
                except Exception:
                    print("      services unhealthy - restarting all", flush=True)
                    await ctl.stop_all()
                    await ctl.start_all()
            await asyncio.sleep(args.cooldown)
    finally:
        await ctl.stop_all()
    print(f"finished: {len(todo) - failures} ok, {failures} failed. Results in {results_dir}")
    return 1 if failures else 0


def main(argv=None) -> int:
    args = resolve(build_parser().parse_args(argv))
    if sys.platform == "win32" and args.users < 450:
        # the selector loop avoids Proactor shutdown noise; it is limited to ~500 sockets, hence the guard
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    return asyncio.run(main_async(args))


if __name__ == "__main__":
    raise SystemExit(main())
