"""Entry point:  python -m analysis.run_analysis --results results --out analysis_out

Reads ``<results>/<config_id>/run_k.json`` (+ ``run_k.requests.csv`` for the time series) and writes
tables (CSV), figures (PNG) and ``summary.md``. Every number in the output is computed from the data; if there
is no data the script says so and writes nothing else.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np
import pandas as pd

from . import figures, tables
from .loading import load_runs
from .stats import mean_ci

NO_DATA = """No result files were found in {path}.

The experiments have not been run yet (they take hours for the full grid). Run, for example:

    ./run_experiments.sh --scenario mixed --repeats 5      # full grid, ~38 h for one scenario
    ./run_experiments.sh --scenario mixed --quick          # reduced grid, minutes (smoke test)

and then re-run this script. See README.md, "Reproducing the results".
"""


def _fault_window(df: pd.DataFrame, scenario: str) -> Optional[Tuple[float, float]]:
    s = df[(df["scenario"] == scenario) & df["fault_window_s"].notna()]
    return tuple(s["fault_window_s"].iloc[0]) if len(s) else None


def _timeseries(results: Path, df: pd.DataFrame, scenario: str, cell: Tuple[int, int, int], bucket_s: float = 5.0):
    """Pooled (all repeats) p99 per time bucket per system, plus the mean adaptive threshold trajectory."""
    series, thr = {}, {}
    duration = float(df[df["scenario"] == scenario]["duration_s"].iloc[0])
    grid = np.arange(0, duration + 1e-9, bucket_s)
    for sysname in ("none", "static", "adaptive"):
        g = tables.cell_runs(df, scenario, sysname, cell)
        if g.empty:
            continue
        lats, tt, trajs = [], [], []
        for _, r in g.iterrows():
            req = results / r["config_id"] / f"run_{int(r['run'])}.requests.csv"
            if not req.exists():
                continue
            d = pd.read_csv(req, usecols=["t_s", "latency_ms", "phase"])
            d = d[d["phase"] == "measured"]
            lats.append(d["latency_ms"].values)
            tt.append(d["t_s"].values)
            if sysname == "adaptive":
                j = json.loads((results / r["config_id"] / f"run_{int(r['run'])}.json").read_text(encoding="utf-8"))
                tr = j["services"]["order-service"].get("resilience", {}).get("targets", {}).get("payment-service", {})
                trace = tr.get("evaluationTrace", [])
                if trace:
                    ts = np.array([p["tSec"] for p in trace])
                    th = np.array([p["threshold"] for p in trace])
                    idx = np.searchsorted(ts, grid, side="right") - 1
                    trajs.append(np.where(idx >= 0, th[np.clip(idx, 0, None)], tr.get("baselineThreshold", cell[2])))
        if not lats:
            continue
        t_all, l_all = np.concatenate(tt), np.concatenate(lats)
        rows = []
        for lo in np.arange(0, duration, bucket_s):
            m = (t_all >= lo) & (t_all < lo + bucket_s)
            if m.sum() >= 20:
                rows.append({"t": lo + bucket_s, "p99": float(np.percentile(l_all[m], 99))})
        series[sysname] = pd.DataFrame(rows)
        if sysname == "adaptive" and trajs:
            thr["adaptive"] = pd.DataFrame({"t": grid, "threshold": np.mean(trajs, axis=0)})
        if sysname == "static":
            thr["static"] = pd.DataFrame({"t": grid, "threshold": cell[2]})
    return series, thr


def coldstart_table(results: Path, df: pd.DataFrame, cell: Tuple[int, int, int], recovery_s: float = 30.0) -> pd.DataFrame:
    """End-to-end latency around the restart of payment-service, per system.

    Windows (relative to the start of the measured period, from the logged restart event):
    pre = before the kill; outage = kill until the service answers /health again; recovery = the next
    ``recovery_s`` seconds (cold JVM); steady = afterwards. Statistics are computed per run from the raw requests
    and then summarised over runs.
    """
    rows = []
    sub = df[(df["scenario"] == "coldstart") & df["restart_at_s"].notna()]
    for sysname in tables.SYSTEM_ORDER:
        g = tables.cell_runs(sub, "coldstart", sysname, cell)
        per_window: Dict[str, List[dict]] = {}
        for _, r in g.iterrows():
            req = results / r["config_id"] / f"run_{int(r['run'])}.requests.csv"
            if not req.exists():
                continue
            d = pd.read_csv(req, usecols=["t_s", "latency_ms", "ok", "phase"])
            d = d[d["phase"] == "measured"]
            a, b = float(r["restart_at_s"]), float(r["restart_back_s"])
            wins = {"pre-restart": (0, a), "outage": (a, b), "recovery (+%gs)" % recovery_s: (b, b + recovery_s),
                    "steady": (b + recovery_s, float(r["duration_s"]))}
            for name, (lo, hi) in wins.items():
                x = d[(d["t_s"] >= lo) & (d["t_s"] < hi)]
                if len(x) >= 5:
                    per_window.setdefault(name, []).append({
                        "avg": x["latency_ms"].mean(), "p95": x["latency_ms"].quantile(0.95),
                        "p99": x["latency_ms"].quantile(0.99), "err": 100 * (1 - x["ok"].mean())})
        for name in ("pre-restart", "outage", "recovery (+%gs)" % recovery_s, "steady"):
            v = per_window.get(name)
            if not v:
                continue
            row = {"system": sysname, "window": name, "n_runs": len(v)}
            for k in ("avg", "p95", "p99"):
                m, lo, hi, _ = mean_ci([x[k] for x in v])
                row[k] = tables.fmt_ci(m, lo, hi)
            m, lo, hi, _ = mean_ci([x["err"] for x in v])
            row["err"] = tables.fmt_ci(m, lo, hi, 1, hi_cap=100.0)
            rows.append(row)
    return pd.DataFrame(rows)


def build(results: Path, out: Path, threshold_pct: float = 15.0, label: str = "") -> int:
    df = load_runs(results)
    if df.empty:
        print(NO_DATA.format(path=results))
        return 0
    out.mkdir(parents=True, exist_ok=True)
    tdir, fdir = out / "tables", out / "figures"
    tdir.mkdir(exist_ok=True)
    fdir.mkdir(exist_ok=True)
    df.to_csv(tdir / "runs.csv", index=False)

    ref, ref_is_requested = tables.pick_reference(df)
    md: List[str] = []
    w = md.append

    # ------------------------------------------------------------------ header and data inventory
    w(f"# Results summary{(' - ' + label) if label else ''}\n")
    w("_Generated by `python -m analysis.run_analysis`. Every number below is computed from the run files in "
      f"`{results}`; nothing is typed in by hand._\n")

    warnings: List[str] = []
    reps = df.groupby(["scenario", "system", "timeout_ms", "retries", "threshold"], dropna=False)["run"].nunique()
    if reps.min() < 5:
        warnings.append(f"Some configurations have fewer than 5 repeats (minimum {int(reps.min())}); confidence "
                        "intervals need n >= 3 and the Mann-Whitney test needs n >= 5 per group to reach p < 0.01.")
    if df["duration_s"].min() < 300:
        warnings.append(f"Runs shorter than the 300 s of the base paper are present (shortest {df['duration_s'].min():g} s); "
                        "tail percentiles of short runs rest on few slow requests.")
    if df["users"].notna().any() and df["users"].min() != 290:
        warnings.append(f"Not all runs use 290 users (min {int(df['users'].min())}).")
    expected = {"none": 12, "static": 36, "adaptive": 36}
    for (sc, sysname), g in df.groupby(["scenario", "system"]):
        n_cells = len(g.groupby(["timeout_ms", "retries", "threshold"], dropna=False))
        if n_cells < expected[sysname]:
            warnings.append(f"`{sc}`/{sysname}: {n_cells} of {expected[sysname]} grid cells present (partial grid).")
    if not ref_is_requested:
        warnings.append(f"The base paper's reference configuration (timeout 2000 ms, 3 retries, threshold 5) is not in "
                        f"the data; headline tables use timeout={ref[0]} ms, retries={ref[1]}, threshold={ref[2]}.")
    cpu_src = sorted({str(x) for c in ("cpu_source_inventory", "cpu_source_payment", "cpu_source_order")
                      for x in df[c].dropna().unique()})
    if "jmx" in cpu_src and "oshi" in cpu_src:
        warnings.append("CPU was measured with different sources across services/runs (OSHI and JVM counter).")
    if warnings:
        w("## Warnings\n")
        for x in warnings:
            w(f"- {x}")
        w("")

    w("## Data inventory\n")
    inv = df.groupby(["scenario", "system"]).agg(runs=("run", "size"), cells=("config_id", "nunique"),
                                                 duration_s=("duration_s", "first")).reset_index()
    w(tables.md_table(inv, ["scenario", "system", "cells", "runs", "duration_s"],
                      ["scenario", "system", "grid cells", "runs", "run length (s)"], "{:.0f}"))
    w("")
    w(f"Reference configuration for the headline tables: **timeout {ref[0]} ms, retries {ref[1]}, failure threshold "
      f"{ref[2]}** "
      f"({'the configuration of the base paper, its Fig 5' if ref_is_requested else 'fallback, see warnings'}).\n")

    # ------------------------------------------------------------------ headline
    w("## 1. Average vs p95 vs p99 for none / static / adaptive\n")
    w("Mean over repeats with 95 % Student-t confidence interval in brackets (clipped at 0, and at 100 % for rates, "
      "because an interval from 2-3 runs can leave the physical range); end-to-end latency measured at the "
      "load generator, all requests (a rejected request is a fast failure and counts). The last columns give the "
      "p99 of *successful* requests only and the error rate, because a breaker can lower latency simply by "
      "failing requests quickly.\n")
    for phase_req, title in (("fault", "During the fault window"), ("overall", "Whole measured run")):
        t = tables.headline_table(df, ref, phase_req)
        if t.empty:
            continue
        t.to_csv(tdir / f"headline_{phase_req}.csv", index=False)
        w(f"### {title}\n")
        w(tables.md_table(t, ["scenario", "system", "n_runs", "avg_ci", "p95_ci", "p99_ci", "ok_p99_ci", "error_ci"],
                          ["scenario", "system", "runs", "avg (ms)", "p95 (ms)", "p99 (ms)", "p99 successful only (ms)",
                           "error rate (%)"]))
        w("")

    cs = coldstart_table(results, df, ref)
    if not cs.empty:
        cs.to_csv(tdir / "coldstart.csv", index=False)
        w("### Cold start: restart of payment-service mid-run\n")
        w("The base paper reports a p95 falling from about 800 ms to about 200 ms after a cold start. Here the "
          "service is killed and restarted at 40 % of the run; latency of the end-to-end requests is split into "
          "the windows before, during and after the restart (outage = until `/health` answers again; recovery = "
          "the following 30 s while the JVM is cold).\n")
        w(tables.md_table(cs, ["system", "window", "n_runs", "avg", "p95", "p99", "err"],
                          ["system", "window", "runs", "avg (ms)", "p95 (ms)", "p99 (ms)", "error rate (%)"]))
        w("")

    # ------------------------------------------------------------------ claim check
    w(f"## 2. Does the base paper's ~{threshold_pct:g} % gain hold at p95 and p99?\n")
    w("**What is being compared.** The base paper reports up to 15 % lower *average* response time under high network "
      "latency for its graph-partitioned traffic management combined with Istio circuit breaking, relative to default "
      "Istio. Its gain is attributed to service co-location, which this project deliberately does not reproduce. The "
      "closest quantity measurable here is the relative reduction of latency that a circuit-breaker system achieves "
      "over the *same* timeout/retry configuration without a breaker. The table therefore tests (a) whether a gain of "
      "that magnitude exists for the average and (b) whether it persists at p95 and p99; it does not test the "
      "base paper's number itself.\n")
    w("*gain = (mean of no-breaker - mean of system) / mean of no-breaker; positive = lower latency. CI: percentile "
      "bootstrap over runs (10 000 resamples, needs n >= 3 per group). p: two-sided Mann-Whitney U, Holm-corrected "
      "across the rows of this table. Verdict compares the CI with the claimed 15 %: it can only say 'holds' if the "
      "whole CI is above 15 %.*\n")
    claim_all = []
    for phase_req, title in (("fault", "During the fault window"), ("overall", "Whole measured run")):
        t = tables.claim_table(df, ref, phase_req, threshold_pct)
        if t.empty:
            continue
        t["phase_req"] = phase_req
        claim_all.append(t)
        t["gain_ci"] = [tables.fmt_ci(g, lo, hi, 1, "%", lo_floor=None) for g, lo, hi in zip(t["gain_pct"], t["ci_lo"], t["ci_hi"])]
        w(f"### {title}\n")
        main = t[t["metric"].isin(["avg", "p95", "p99", "p99 (successful only)"])]
        w(tables.md_table(main, ["scenario", "system", "metric", "gain_ci", "mw_p", "mw_p_holm", "cliffs_delta", "verdict"],
                          ["scenario", "system", "metric", "gain vs no breaker", "p (MWU)", "p (Holm)",
                           "Cliff's d (system vs none; negative = lower)", "vs 15 %"], "{:.3f}"))
        w("")
        errs = t[t["metric"] == "error rate (pp change)"]
        if len(errs):
            w("Change in error rate relative to no breaker (percentage points; positive = more failed requests):\n")
            w(tables.md_table(errs, ["scenario", "system", "gain_pct", "mw_p"],
                              ["scenario", "system", "error rate change (pp)", "p (MWU)"], "{:.2f}"))
            w("")
    if claim_all:
        allc = pd.concat(claim_all)
        allc.to_csv(tdir / "claim_check.csv", index=False)
        w("**Auto-generated reading of the table** (arithmetic only):\n")
        for (ph, sc, sysname), g in allc[allc["phase_req"] == "fault"].groupby(["phase", "scenario", "system"]):
            a, p95, p99 = (g[g["metric"] == m]["gain_pct"] for m in ("avg", "p95", "p99"))
            err = g[g["metric"] == "error rate (pp change)"]["gain_pct"]
            if a.empty or p99.empty:
                continue
            cost = f"; error rate {err.iloc[0]:+.1f} pp vs no breaker" if len(err) else ""
            w(f"- `{sc}`, {sysname}: average {a.iloc[0]:+.1f} %, p95 {p95.iloc[0]:+.1f} %, p99 {p99.iloc[0]:+.1f} % "
              f"({'the tail gain is smaller than the average gain' if p99.iloc[0] < a.iloc[0] else 'the tail gain is at least as large as the average gain'}"
              f"{cost}).")
        w("")

    # ------------------------------------------------------------------ whole grid
    w("## 3. Configuration sensitivity (timeout x retries x threshold)\n")
    w("The base paper measured one fixed middleware configuration. Here the same comparison is made in every grid "
      "cell; each cell compares a system with the no-breaker system at the same timeout and retry count.\n")
    for phase_req, title in (("fault", "fault window"), ("overall", "whole run")):
        cells = tables.cell_means(df, phase_req)
        if cells.empty:
            continue
        cells.to_csv(tdir / f"cell_means_{phase_req}.csv", index=False)
        gains = tables.matched_gains(cells)
        if gains.empty:
            continue
        gains.to_csv(tdir / f"matched_gains_{phase_req}.csv", index=False)
        summ = tables.grid_gain_summary(gains, threshold_pct)
        summ.to_csv(tdir / f"grid_gain_summary_{phase_req}.csv", index=False)
        w(f"### Gain over the no-breaker system across the grid ({title})\n")
        w(tables.md_table(summ, ["scenario", "system", "metric", "cells", "median_gain_pct", "min_gain_pct",
                                 "max_gain_pct", "cells_ge_threshold", "cells_worse_than_none"],
                          ["scenario", "system", "metric", "cells", "median gain %", "min %", "max %",
                           f"cells >= {threshold_pct:g} %", "cells worse than none"]))
        w("")
        sens = tables.sensitivity_summary(cells)
        if not sens.empty:
            sens.to_csv(tdir / f"sensitivity_{phase_req}.csv", index=False)
            w(f"### Spread of p99 across the grid ({title})\n")
            w(tables.md_table(sens, ["scenario", "system", "cells", "p99_min_ms", "p99_max_ms", "max_over_min",
                                     "best_cell", "worst_cell"],
                              ["scenario", "system", "cells", "min p99 (ms)", "max p99 (ms)", "max/min", "best cell",
                               "worst cell"], "{:.1f}"))
            w("")
        figures.gain_strip(gains, fdir / f"gain_strip_{phase_req}.png", threshold_pct)
        w(f"![per-cell gain ({title})](figures/gain_strip_{phase_req}.png)\n")
        for sc in sorted(cells["scenario"].unique()):
            p = figures.sensitivity_map(cells, sc, fdir / f"heatmap_p99_{sc}_{phase_req}.png")
            q = figures.gain_map(gains, sc, fdir / f"gain_map_p99_{sc}_{phase_req}.png")
            if p:
                w(f"- configuration-sensitivity map, {sc} ({title}): `figures/heatmap_p99_{sc}_{phase_req}.png`")
            if q:
                w(f"- p99 gain map vs. no breaker, {sc} ({title}): `figures/gain_map_p99_{sc}_{phase_req}.png`")
        w("")

    # ------------------------------------------------------------------ adaptive vs static
    w("## 4. Adaptive vs static breaker (head to head)\n")
    w("Same statistics as section 2, with the static breaker as the baseline (positive gain = adaptive has lower "
      "latency than static at the same configuration).\n")
    for phase_req, title in (("fault", "During the fault window"), ("overall", "Whole measured run")):
        t = tables.claim_table(df, ref, phase_req, threshold_pct, versus="static", systems=("adaptive",))
        if t.empty:
            continue
        t["gain_ci"] = [tables.fmt_ci(g, lo, hi, 1, "%", lo_floor=None) for g, lo, hi in zip(t["gain_pct"], t["ci_lo"], t["ci_hi"])]
        t.to_csv(tdir / f"adaptive_vs_static_{phase_req}.csv", index=False)
        w(f"### {title}\n")
        w(tables.md_table(t[t["metric"].isin(["avg", "p95", "p99", "p99 (successful only)"])],
                          ["scenario", "metric", "gain_ci", "mw_p", "mw_p_holm"],
                          ["scenario", "metric", "adaptive vs static", "p (MWU)", "p (Holm)"], "{:.3f}"))
        w("")
    act = tables.breaker_activity(df, ref)
    if not act.empty:
        act.to_csv(tdir / "breaker_activity.csv", index=False)
        w("### Breaker activity (mean per run, reference configuration)\n")
        w(tables.md_table(act, ["scenario", "system", "n_runs", "payment_opens", "payment_open_s", "inventory_opens",
                                "inventory_open_s", "tighten_events", "relax_events"],
                          ["scenario", "system", "runs", "payment opens", "payment open (s)", "inventory opens",
                           "inventory open (s)", "tighten events", "relax events"]))
        w("")

    # ------------------------------------------------------------------ time series
    ts_done = []
    for sc in sorted(df["scenario"].unique()):
        series, thr = _timeseries(results, df, sc, ref)
        if series:
            p = figures.timeseries_figure(series, thr, _fault_window(df, sc),
                                          f"{sc}: end-to-end p99 over time (pooled over repeats), "
                                          f"timeout {ref[0]} ms, {ref[1]} retries, threshold {ref[2]}",
                                          fdir / f"timeseries_{sc}.png")
            if p:
                ts_done.append(sc)
    if ts_done:
        w("### Time series\n")
        for sc in ts_done:
            w(f"![{sc} p99 over time](figures/timeseries_{sc}.png)\n")

    # ------------------------------------------------------------------ CPU
    w("## 5. CPU\n")
    w("CPU is the process CPU time divided by wall-clock time per service (100 % = one core), averaged over the "
      "measured run; the efficiency column is CPU-seconds spent per 1000 successful requests. Reference "
      "configuration only; whiskers are 95 % confidence intervals over repeats.\n")
    cpu = tables.cpu_table(df, ref)
    if not cpu.empty:
        cpu.to_csv(tdir / "cpu.csv", index=False)
        figures.cpu_figure(cpu, fdir / "cpu_per_service.png")
        figures.cpu_efficiency_figure(cpu, fdir / "cpu_per_1k_requests.png")
        w("![CPU per service](figures/cpu_per_service.png)\n")
        w("![CPU per 1000 successful requests](figures/cpu_per_1k_requests.png)\n")
        piv = cpu.pivot_table(index=["scenario", "service"], columns="system", values="cpu_pct").reset_index()
        cols = [c for c in ("none", "static", "adaptive") if c in piv.columns]
        w(tables.md_table(piv, ["scenario", "service"] + cols, ["scenario", "service"] + [f"{c} (% of 1 core)" for c in cols]))
        w("")
        w(f"CPU source(s): {', '.join(cpu_src) or 'unknown'}.\n")

    # ------------------------------------------------------------------ method notes
    w("## Method notes and limitations\n")
    for line in [
        "Latency is measured at the load generator (closed loop, think time 1-3 s per virtual user). A closed loop "
        "under-samples stalls ('coordinated omission'): during a slow period fewer requests are issued, so tail "
        "percentiles of a stalled system can look better than under an open-loop generator.",
        "Run-level percentiles are computed from the raw per-request latencies of each run, never as averages of "
        "interval percentiles; the CI/test unit is the run.",
        "Within a repeat k all systems and configurations share the same seed, so the same requests are faulted; "
        "this is a paired design, analysed here with the (conservative) independent-sample Mann-Whitney test.",
        "The no-breaker system uses the same timeout and retry policy as the others; only the breaker differs.",
        "Resilience4j is not Istio: see README, 'Resilience4j vs Istio outlier detection'.",
        "A single machine hosts the load generator and the three services; CPU contention between them is part of "
        "the measurement and results are only comparable within one machine and session.",
    ]:
        w(f"- {line}")
    (out / "summary.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"wrote {out / 'summary.md'} ({len(df)} runs, {len(list(fdir.glob('*.png')))} figures)")
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="analysis")
    p.add_argument("--results", default="results", help="results directory written by the harness")
    p.add_argument("--out", default="analysis_out", help="output directory (tables, figures, summary.md)")
    p.add_argument("--threshold", type=float, default=15.0, help="claimed gain in percent (base paper: 15)")
    p.add_argument("--label", default="", help="text appended to the summary title, e.g. SMOKE DATA")
    a = p.parse_args(argv)
    return build(Path(a.results), Path(a.out), a.threshold, a.label)


if __name__ == "__main__":
    sys.exit(main())
