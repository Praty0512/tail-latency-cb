"""One experiment run: configure -> warm up -> measure (faults, events) -> collect -> write results."""
from __future__ import annotations

import asyncio
import csv
import json
import os
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import aiohttp
import numpy as np

from .loadgen import LoadConfig, LoadGenerator, interval_rows, is_error, summarize
from .scenarios import Scenario
from .services import ServiceController, base_url, wait_healthy
from .sweep import RunSpec

CSV_HEADER = ["timestamp", "service", "p50", "p95", "p99", "avg", "error_rate", "cpu_pct", "cb_state", "config_id",
              "direction", "endpoint", "count", "errors", "rejected", "t_s", "cb_threshold"]
SERVICES = ("inventory-service", "payment-service", "order-service")


@dataclass
class RunParams:
    users: int = 290
    ramp_rate: float = 100.0
    think_min: float = 1.0
    think_max: float = 3.0
    duration_s: float = 300.0
    warmup_s: float = 15.0
    interval_s: float = 5.0
    open_wait_ms: int = 10_000
    half_open_probes: int = 3
    retry_policy: str = "ALL_FAILURES"
    adaptive: Dict[str, Any] = field(default_factory=dict)
    extra_first_warmup_s: float = 20.0


class Admin:
    """Tiny async client for the services' admin endpoints."""

    def __init__(self, session: aiohttp.ClientSession):
        self.s = session

    async def call(self, method: str, service: str, path: str, body: Any = None, attempts: int = 3) -> Tuple[int, Any]:
        last: Optional[Exception] = None
        for _ in range(attempts):
            try:
                async with self.s.request(method, base_url(service) + path, json=body) as r:
                    text = await r.text()
                    try:
                        return r.status, json.loads(text)
                    except ValueError:
                        return r.status, text
            except (aiohttp.ClientError, asyncio.TimeoutError, OSError) as e:
                last = e
                await asyncio.sleep(0.5)
        raise RuntimeError(f"{method} {service}{path} failed: {last}")

    async def ok(self, method: str, service: str, path: str, body: Any = None) -> Any:
        status, data = await self.call(method, service, path, body)
        if status != 200:
            raise RuntimeError(f"{method} {service}{path} -> HTTP {status}: {data}")
        return data


def _iso(epoch_s: float) -> str:
    return datetime.fromtimestamp(epoch_s, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def _parse_iso(ts: str) -> float:
    return datetime.strptime(ts.replace("Z", "+0000"), "%Y-%m-%dT%H:%M:%S.%f%z").timestamp() \
        if "." in ts else datetime.strptime(ts.replace("Z", "+0000"), "%Y-%m-%dT%H:%M:%S%z").timestamp()


def _fmt(v: Optional[float], digits: int = 3) -> str:
    return "" if v is None else f"{v:.{digits}f}"


def service_rows(service: str, export: dict, t0: float) -> List[List[str]]:
    rows = []
    for r in export["intervals"]:
        t_s = _parse_iso(r["timestamp"]) - t0
        rows.append([r["timestamp"], service, _fmt(r["p50"]), _fmt(r["p95"]), _fmt(r["p99"]), _fmt(r["avg"]),
                     _fmt(r["errorRate"], 5), _fmt(r["cpuPct"]), r["cbState"], r["configId"], r["direction"],
                     r["endpoint"], r["count"], r["errors"], r["rejected"], f"{t_s:.1f}",
                     "" if r["cbThreshold"] < 0 else r["cbThreshold"]])
    return rows


def _atomic_write(path: Path, text: str) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "w", encoding="utf-8", newline="") as f:  # newline="": no CRLF translation on Windows
        f.write(text)
    os.replace(tmp, path)


def run_files(results_dir: Path, spec: RunSpec) -> Dict[str, Path]:
    d = results_dir / spec.config_id
    k = spec.repeat
    return {"dir": d, "csv": d / f"run_{k}.csv", "json": d / f"run_{k}.json", "requests": d / f"run_{k}.requests.csv"}


def is_done(results_dir: Path, spec: RunSpec) -> bool:
    f = run_files(results_dir, spec)
    return f["csv"].exists() and f["json"].exists() and f["requests"].exists()


async def run_one(spec: RunSpec, scenario: Scenario, params: RunParams, ctl: ServiceController,
                  results_dir: Path, first_run: bool = False, log=print) -> dict:
    duration = params.duration_s
    warmup = params.warmup_s + (params.extra_first_warmup_s if first_run else 0.0)
    warmup = max(warmup, 1.5 * params.users / params.ramp_rate + 3.0)  # warm-up must cover the ramp-up

    timeout = aiohttp.ClientTimeout(total=30)
    async with aiohttp.ClientSession(timeout=timeout) as session:
        admin = Admin(session)
        await ctl.ensure_healthy()

        # ---- 1. configuration
        for svc in ("inventory-service", "payment-service"):
            await admin.ok("DELETE", svc, "/admin/fault")
        cfg = {
            "configId": spec.config_id, "system": spec.system.upper(),
            "timeoutMs": spec.timeout_ms, "retries": spec.retries,
            "failureThreshold": spec.threshold if spec.threshold is not None else 5,
            "openWaitMs": params.open_wait_ms, "halfOpenProbes": params.half_open_probes,
            "retryOn": params.retry_policy, "adaptive": params.adaptive or {},
        }
        await admin.ok("POST", "order-service", "/admin/config", cfg)

        # ---- 2. warm-up under full load
        lg = LoadGenerator(LoadConfig(users=params.users, ramp_rate=params.ramp_rate, think_min=params.think_min,
                                      think_max=params.think_max, seed=spec.seed))
        await lg.start()
        await asyncio.sleep(warmup)

        # ---- 3. measured period
        t0 = time.time()
        t0_ms = int(t0 * 1000)
        start_body = {"configId": spec.config_id, "scenario": spec.scenario, "system": spec.system,
                      "run": spec.repeat, "intervalMs": int(params.interval_s * 1000)}
        await asyncio.gather(*[admin.ok("POST", s, "/admin/run/start", start_body) for s in SERVICES])
        fault_specs = scenario.fault_specs(duration, spec.seed, t0_ms)
        await asyncio.gather(*[admin.ok("POST", s, "/admin/fault", body) for s, body in fault_specs.items()])

        events_log: List[dict] = []
        parts: Dict[str, List[dict]] = {}
        event_task = asyncio.create_task(
            _run_events(scenario.timed_events(duration), t0, ctl, admin, start_body, fault_specs, events_log, parts))
        await asyncio.sleep(max(0.0, t0 + duration - time.time()))
        t_end = time.time()

        # ---- 4. stop load, stop run, collect
        records = await lg.stop()
        if not event_task.done():
            event_task.cancel()
        try:
            await event_task
        except asyncio.CancelledError:
            pass
        exports: Dict[str, Any] = {}
        for svc in SERVICES:
            await admin.ok("POST", svc, "/admin/run/stop")
            final = await admin.ok("GET", svc, "/admin/run/export?format=json")
            exports[svc] = {"parts": parts[svc] + [final]} if svc in parts else final
        for svc in ("inventory-service", "payment-service"):
            await admin.ok("DELETE", svc, "/admin/fault")

    return _write_results(spec, scenario, params, results_dir, t0, t_end, records, exports, events_log, warmup)


async def _run_events(events: List[dict], t0: float, ctl: ServiceController, admin: Admin, start_body: dict,
                      fault_specs: Dict[str, dict], events_log: List[dict], parts: Dict[str, List[dict]]) -> None:
    for ev in events:
        await asyncio.sleep(max(0.0, t0 + ev["at_s"] - time.time()))
        svc = ev["service"]
        if ev["action"] != "restart":
            raise ValueError(f"unknown event action {ev['action']}")
        # Keep what the service measured before it dies; the new JVM starts with empty metrics.
        before = await admin.ok("GET", svc, "/admin/run/export?format=json")
        parts.setdefault(svc, []).append(before)
        issued = time.time()
        await ctl.restart(svc)
        down_s = await wait_healthy(svc, timeout_s=120)
        back = time.time()
        await admin.ok("POST", svc, "/admin/run/start", start_body)
        if svc in fault_specs:
            await admin.ok("POST", svc, "/admin/fault", fault_specs[svc])
        events_log.append({"action": "restart", "service": svc, "at_s": issued - t0, "healthy_again_at_s": back - t0,
                           "waited_for_health_s": down_s})


def _fault_window_s(scenario: Scenario, duration: float) -> Optional[Tuple[float, float]]:
    w = scenario.fault_window_frac()
    return None if w is None else (w[0] * duration, w[1] * duration)


def _write_results(spec: RunSpec, scenario: Scenario, params: RunParams, results_dir: Path, t0: float, t_end: float,
                   records: list, exports: Dict[str, Any], events_log: List[dict], warmup: float) -> dict:
    duration = params.duration_s
    arr_t = np.array([r[0] for r in records], dtype=float) - t0
    arr_lat = np.array([r[1] for r in records], dtype=float)
    arr_status = np.array([r[2] for r in records], dtype=int)
    arr_err = np.array([is_error(int(s)) for s in arr_status], dtype=bool)

    measured = (arr_t >= 0) & (arr_t < duration)
    if int(measured.sum()) == 0:
        raise RuntimeError("no measured requests were recorded - are the services reachable?")
    t_m, lat_m, err_m = arr_t[measured], arr_lat[measured], arr_err[measured]

    e2e = {"overall": summarize(lat_m, err_m, duration)}
    fw = _fault_window_s(scenario, duration)
    if fw:
        lo, hi = fw
        for name, m in (("pre", t_m < lo), ("fault", (t_m >= lo) & (t_m < hi)), ("post", t_m >= hi)):
            span = {"pre": lo, "fault": hi - lo, "post": duration - hi}[name]
            e2e[name] = summarize(lat_m[m], err_m[m], span)

    files = run_files(results_dir, spec)
    files["dir"].mkdir(parents=True, exist_ok=True)

    # ---- CSV: server-side interval rows of all services + client-side end-to-end rows
    rows: List[List[str]] = []
    for svc in SERVICES:
        ex = exports[svc]
        for part in (ex["parts"] if "parts" in ex else [ex]):
            rows.extend(service_rows(svc, part, t0))
    for r in interval_rows(t_m, lat_m, err_m, params.interval_s, duration):
        rows.append([_iso(t0 + r["t_end_s"]), "loadgen", _fmt(r["p50"]), _fmt(r["p95"]), _fmt(r["p99"]), _fmt(r["avg"]),
                     _fmt(r["error_rate"], 5), "", "NA", spec.config_id, "e2e", "POST /api/orders",
                     r["count"], r["errors"], 0, f"{r['t_end_s']:.1f}", ""])
    rows.sort(key=lambda x: (float(x[15]), x[1], x[10], x[11]))
    out = [",".join(CSV_HEADER)]
    for r in rows:
        out.append(",".join(f'"{c}"' if isinstance(c, str) and "," in c else str(c) for c in r))
    _atomic_write(files["csv"], "\n".join(out) + "\n")

    # ---- raw per-request log
    lines = ["t_s,latency_ms,status,ok,phase,user,key"]
    for (st, lat, status, uid, key), t_rel, err in zip(records, arr_t, arr_err):
        phase = "warmup" if t_rel < 0 else ("measured" if t_rel < duration else "drain")
        lines.append(f"{t_rel:.4f},{lat:.3f},{status},{0 if err else 1},{phase},{uid},{key}")
    _atomic_write(files["requests"], "\n".join(lines) + "\n")

    # ---- JSON
    meta = {
        "configId": spec.config_id, "scenario": spec.scenario, "system": spec.system, "timeoutMs": spec.timeout_ms,
        "retries": spec.retries, "failureThreshold": spec.threshold, "run": spec.repeat, "seed": spec.seed,
        "startEpochMs": int(t0 * 1000), "endEpochMs": int(t_end * 1000), "durationS": duration, "warmupS": warmup,
        "faultWindowS": list(fw) if fw else None, "events": events_log,
        "load": {"users": params.users, "rampRate": params.ramp_rate, "thinkMin": params.think_min,
                 "thinkMax": params.think_max},
        "resilience": {"openWaitMs": params.open_wait_ms, "halfOpenProbes": params.half_open_probes,
                       "retryPolicy": params.retry_policy, "adaptive": params.adaptive},
        "scenarioDescription": scenario.description,
    }
    _atomic_write(files["json"], json.dumps({"meta": meta, "e2e": e2e, "services": exports}, indent=1))
    return {"configId": spec.config_id, "run": spec.repeat, "e2e": e2e["overall"], "events": events_log}
