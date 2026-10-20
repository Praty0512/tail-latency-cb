"""Loads ``<results>/<config_id>/run_k.json`` files into one row per run."""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path
from typing import List

import pandas as pd

PHASES = ("overall", "pre", "fault", "post")
STATS = ("count", "errors", "errorRate", "throughputRps", "goodputRps", "avg", "p50", "p95", "p99",
         "okAvg", "okP95", "okP99")
SERVICES = ("inventory-service", "payment-service", "order-service")
TARGETS = ("inventory-service", "payment-service")


def _iso(ts: str) -> float:
    fmt = "%Y-%m-%dT%H:%M:%S.%f%z" if "." in ts else "%Y-%m-%dT%H:%M:%S%z"
    return datetime.strptime(ts.replace("Z", "+0000"), fmt).timestamp()


def _parts(svc_export: dict) -> List[dict]:
    return svc_export["parts"] if "parts" in svc_export else [svc_export]


def _wall_s(part: dict) -> float:
    m = part["meta"]
    end = m.get("endEpochMs") or 0
    if end > 0:
        return (end - m["startEpochMs"]) / 1000.0
    rows = part.get("intervals") or []
    return (_iso(rows[-1]["timestamp"]) - m["startEpochMs"] / 1000.0) if rows else 0.0


def _open_time(transitions: List[dict], breaker: str, run_end_s: float) -> float:
    """Seconds the breaker spent OPEN, from its transition log."""
    t_open = None
    total = 0.0
    for t in sorted((t for t in transitions if t["breaker"] == breaker), key=lambda x: x["tSec"]):
        if t["to"] == "OPEN":
            t_open = t["tSec"]
        elif t["from"] == "OPEN" and t_open is not None:
            total += t["tSec"] - t_open
            t_open = None
    if t_open is not None:
        total += max(0.0, run_end_s - t_open)
    return total


def load_runs(results_dir) -> pd.DataFrame:
    rows = []
    for jf in sorted(Path(results_dir).glob("*/run_*.json")):
        j = json.loads(jf.read_text(encoding="utf-8"))
        meta, e2e = j["meta"], j["e2e"]
        row = {
            "config_id": meta["configId"], "scenario": meta["scenario"], "system": meta["system"],
            "timeout_ms": meta["timeoutMs"], "retries": meta["retries"], "threshold": meta["failureThreshold"],
            "run": meta["run"], "seed": meta["seed"], "duration_s": meta["durationS"], "warmup_s": meta["warmupS"],
            "fault_window_s": meta.get("faultWindowS"), "users": meta.get("load", {}).get("users"),
        }
        for phase in PHASES:
            p = e2e.get(phase)
            for st in STATS:
                row[f"{phase}_{st}"] = p.get(st) if p else None
        ev = [e for e in meta.get("events", []) if e.get("action") == "restart"]
        row["restart_at_s"] = ev[0]["at_s"] if ev else None
        row["restart_back_s"] = ev[0]["healthy_again_at_s"] if ev else None
        order = j["services"].get("order-service", {})
        transitions = (order.get("cbTransitions") or []) if "parts" not in order else order["parts"][-1].get("cbTransitions", [])
        for svc in SERVICES:
            parts = _parts(j["services"][svc])
            cpu_s = sum(p["meta"].get("cpuSeconds") or 0.0 for p in parts)
            wall = sum(_wall_s(p) for p in parts)
            short = svc.split("-")[0]
            row[f"cpu_seconds_{short}"] = cpu_s
            row[f"cpu_pct_{short}"] = 100.0 * cpu_s / wall if wall > 0 else None
            row[f"cpu_source_{short}"] = parts[0]["meta"].get("cpuSource")
            for part in parts:
                for s in part.get("summary", []):
                    if s["direction"] == "inbound" and s.get("p99") is not None:
                        row[f"srv_p99_{short}"] = s["p99"]
        for tgt in TARGETS:
            short = tgt.split("-")[0]
            row[f"cb_opens_{short}"] = sum(1 for t in transitions if t["breaker"] == tgt and t["to"] == "OPEN")
            row[f"cb_open_s_{short}"] = _open_time(transitions, tgt, meta["durationS"])
            tgt_res = (order.get("resilience", {}) if "parts" not in order else order["parts"][-1].get("resilience", {}))
            ad = tgt_res.get("targets", {}).get(tgt, {}).get("adaptationEvents", [])
            row[f"adapt_tighten_{short}"] = sum(1 for e in ad if e["action"] == "TIGHTEN")
            row[f"adapt_relax_{short}"] = sum(1 for e in ad if e["action"] == "RELAX")
        rows.append(row)
    return pd.DataFrame(rows)
