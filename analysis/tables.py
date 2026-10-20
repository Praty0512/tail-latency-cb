"""Tables computed from the per-run DataFrame. Every number comes from the data; nothing is hard-coded."""
from __future__ import annotations

import zlib
from typing import Dict, List, Optional, Tuple

import numpy as np
import pandas as pd

from .stats import bootstrap_improvement_ci, cliffs_delta, holm, improvement_pct, mannwhitney_p, mean_ci, verdict

SYSTEM_ORDER = ["none", "static", "adaptive"]
FAULT_SCENARIOS = ["delay", "errors", "mixed"]
# The base paper's own Istio configuration (its Fig 5): 3 retries, 2 s per-try timeout, eject after 5 errors.
REFERENCE = (2000, 3, 5)
METRICS = ("avg", "p95", "p99")


def phase_for(scenario: str, requested: str, df: pd.DataFrame) -> Optional[str]:
    """'fault' only exists for scenarios with a fault window; fall back to 'overall' otherwise."""
    if requested == "fault":
        sub = df[(df["scenario"] == scenario)]
        return "fault" if len(sub) and sub["fault_count"].notna().any() else None
    return requested


def pick_reference(df: pd.DataFrame, wanted: Tuple[int, int, int] = REFERENCE) -> Tuple[Tuple[int, int, int], bool]:
    """The (timeout, retries, threshold) cell used for headline tables.

    Returns (cell, is_the_requested_one). If the requested cell is not in the data (e.g. a --quick grid), the cell
    present for the most systems and runs is used instead and the caller must say so.
    """
    def has(t, r, f):
        s = df[(df["timeout_ms"] == t) & (df["retries"] == r)]
        return (len(s[(s["system"] == "none")]) > 0 and len(s[(s["system"] == "static") & (s["threshold"] == f)]) > 0
                and len(s[(s["system"] == "adaptive") & (s["threshold"] == f)]) > 0)
    if has(*wanted):
        return wanted, True
    best, best_n = None, -1
    for (t, r), g in df[df["system"] != "none"].groupby(["timeout_ms", "retries"]):
        for f in sorted(g["threshold"].dropna().unique()):
            f = int(f)
            if has(t, r, f):
                n = len(df[(df["timeout_ms"] == t) & (df["retries"] == r) &
                           ((df["threshold"] == f) | (df["system"] == "none"))])
                if n > best_n:
                    best, best_n = (int(t), int(r), f), n
    return (best if best else wanted), False


def cell_runs(df: pd.DataFrame, scenario: str, system: str, cell: Tuple[int, int, int]) -> pd.DataFrame:
    t, r, f = cell
    s = df[(df["scenario"] == scenario) & (df["system"] == system) & (df["timeout_ms"] == t) & (df["retries"] == r)]
    return s if system == "none" else s[s["threshold"] == f]


def fmt_ci(m: float, lo: float, hi: float, digits: int = 0, unit: str = "",
           lo_floor: Optional[float] = 0.0, hi_cap: Optional[float] = None) -> str:
    """'mean [lo, hi]'. Latencies and rates cannot be negative and rates cannot exceed 100 %, but a Student-t
    interval from 2-3 runs can; such bounds are clipped to the physical range (``lo_floor``/``hi_cap``;
    pass ``lo_floor=None`` for signed quantities such as gains)."""
    if np.isnan(m):
        return "-"
    if np.isnan(lo):
        return f"{m:.{digits}f}{unit} (n=1)"
    if lo_floor is not None:
        lo = max(lo, lo_floor)
    if hi_cap is not None:
        hi = min(hi, hi_cap)
    return f"{m:.{digits}f}{unit} [{lo:.{digits}f}, {hi:.{digits}f}]"


# ------------------------------------------------------------------------------------------ headline table

def headline_table(df: pd.DataFrame, cell: Tuple[int, int, int], phase_req: str) -> pd.DataFrame:
    """avg vs p95 vs p99 (and error rate) for none / static / adaptive in every scenario, at one config cell."""
    rows = []
    for sc in sorted(df["scenario"].unique()):
        ph = phase_for(sc, phase_req, df)
        if ph is None:
            continue
        for sysname in SYSTEM_ORDER:
            g = cell_runs(df, sc, sysname, cell)
            if g.empty:
                continue
            row = {"scenario": sc, "phase": ph, "system": sysname, "n_runs": len(g)}
            for m in METRICS:
                mean, lo, hi, _ = mean_ci(g[f"{ph}_{m}"])
                row[f"{m}_ms"] = mean
                row[f"{m}_ci"] = fmt_ci(mean, lo, hi)
            for tag, col in (("ok_p99", f"{ph}_okP99"),):
                mean, lo, hi, _ = mean_ci(g[col])
                row[f"{tag}_ms"] = mean
                row[f"{tag}_ci"] = fmt_ci(mean, lo, hi)
            mean, lo, hi, _ = mean_ci(g[f"{ph}_errorRate"] * 100)
            row["error_pct"] = mean
            row["error_ci"] = fmt_ci(mean, lo, hi, digits=1, hi_cap=100.0)
            rows.append(row)
    return pd.DataFrame(rows)


# ------------------------------------------------------------------------------------------ claim check

def _gain_row(base: pd.Series, other: pd.Series, threshold: float, seed: int) -> dict:
    point, lo, hi = bootstrap_improvement_ci(base, other, seed=seed)
    return {"gain_pct": point, "ci_lo": lo, "ci_hi": hi, "mw_p": mannwhitney_p(base, other),
            "cliffs_delta": cliffs_delta(other, base), "verdict": verdict(lo, hi, point, threshold),
            "n_base": int(base.notna().sum()), "n_other": int(other.notna().sum())}


def claim_table(df: pd.DataFrame, cell: Tuple[int, int, int], phase_req: str, threshold: float = 15.0,
                versus: str = "none", systems: Tuple[str, ...] = ("static", "adaptive")) -> pd.DataFrame:
    """Gain of each breaker system relative to ``versus`` for avg / p95 / p99 at one config cell.

    gain = (mean(versus) - mean(system)) / mean(versus) * 100, i.e. *positive = lower latency than the baseline*.
    ``versus='none'`` compares at the same (timeout, retries) - the breaker is the only difference.
    """
    rows = []
    for sc in sorted(df["scenario"].unique()):
        ph = phase_for(sc, phase_req, df)
        if ph is None:
            continue
        base_runs = cell_runs(df, sc, versus, cell)
        for sysname in systems:
            if sysname == versus:
                continue
            g = cell_runs(df, sc, sysname, cell)
            if g.empty or base_runs.empty:
                continue
            for m in METRICS:
                r = _gain_row(base_runs[f"{ph}_{m}"], g[f"{ph}_{m}"], threshold, seed=zlib.crc32(f"{sc}|{sysname}|{m}".encode()))
                r.update({"scenario": sc, "phase": ph, "system": sysname, "versus": versus, "metric": m})
                rows.append(r)
            # success-only p99 and error rate difference (a breaker can "win" latency by failing fast)
            r = _gain_row(base_runs[f"{ph}_okP99"], g[f"{ph}_okP99"], threshold, seed=1)
            r.update({"scenario": sc, "phase": ph, "system": sysname, "versus": versus, "metric": "p99 (successful only)"})
            rows.append(r)
            d_err = (g[f"{ph}_errorRate"].mean() - base_runs[f"{ph}_errorRate"].mean()) * 100
            rows.append({"scenario": sc, "phase": ph, "system": sysname, "versus": versus,
                         "metric": "error rate (pp change)", "gain_pct": d_err, "ci_lo": np.nan, "ci_hi": np.nan,
                         "mw_p": mannwhitney_p(base_runs[f"{ph}_errorRate"], g[f"{ph}_errorRate"]),
                         "cliffs_delta": np.nan, "verdict": "", "n_base": len(base_runs), "n_other": len(g)})
    out = pd.DataFrame(rows)
    if out.empty:
        return out
    mask = out["metric"].isin(list(METRICS) + ["p99 (successful only)"])
    out["mw_p_holm"] = np.nan
    out.loc[mask, "mw_p_holm"] = holm(out.loc[mask, "mw_p"])
    return out


# ------------------------------------------------------------------------------------------ whole-grid view

def cell_means(df: pd.DataFrame, phase_req: str) -> pd.DataFrame:
    """One row per (scenario, system, timeout, retries, threshold) with the mean over repeats of every metric."""
    rows = []
    for sc in sorted(df["scenario"].unique()):
        ph = phase_for(sc, phase_req, df)
        if ph is None:
            continue
        sub = df[df["scenario"] == sc].copy()
        sub["threshold_key"] = sub["threshold"].fillna(-1)
        for keys, g in sub.groupby(["system", "timeout_ms", "retries", "threshold_key"]):
            row = {"scenario": sc, "phase": ph, "system": keys[0], "timeout_ms": keys[1], "retries": keys[2],
                   "threshold": None if keys[3] == -1 else int(keys[3]), "n_runs": len(g)}
            for m in METRICS:
                row[m] = g[f"{ph}_{m}"].mean()
            row["okP99"] = g[f"{ph}_okP99"].mean()
            row["error_pct"] = g[f"{ph}_errorRate"].mean() * 100
            rows.append(row)
    return pd.DataFrame(rows)


def matched_gains(cells: pd.DataFrame, versus: str = "none") -> pd.DataFrame:
    """Per grid cell, relative gain of static/adaptive over ``versus`` at the same (timeout, retries)."""
    rows = []
    for sc, g in cells.groupby("scenario"):
        base = g[g["system"] == versus].set_index(["timeout_ms", "retries"])
        for _, r in g[g["system"] != versus].iterrows():
            key = (r["timeout_ms"], r["retries"])
            if key not in base.index:
                continue
            b = base.loc[key]
            row = {"scenario": sc, "phase": r["phase"], "system": r["system"], "timeout_ms": r["timeout_ms"],
                   "retries": r["retries"], "threshold": r["threshold"]}
            for m in METRICS + ("okP99",):
                row[f"gain_{m}"] = improvement_pct(b[m], r[m])
            row["d_error_pp"] = r["error_pct"] - b["error_pct"]
            rows.append(row)
    return pd.DataFrame(rows)


def grid_gain_summary(gains: pd.DataFrame, threshold: float = 15.0) -> pd.DataFrame:
    """Does the gain depend on the configuration? Spread of the matched per-cell gain over the whole grid."""
    rows = []
    for (sc, sysname), g in gains.groupby(["scenario", "system"]):
        for m in METRICS:
            v = g[f"gain_{m}"].dropna()
            if v.empty:
                continue
            rows.append({"scenario": sc, "system": sysname, "metric": m, "cells": len(v),
                         "median_gain_pct": v.median(), "min_gain_pct": v.min(), "max_gain_pct": v.max(),
                         "cells_ge_threshold": int((v >= threshold).sum()),
                         "share_ge_threshold": float((v >= threshold).mean()),
                         "cells_worse_than_none": int((v < 0).sum())})
    return pd.DataFrame(rows)


def sensitivity_summary(cells: pd.DataFrame) -> pd.DataFrame:
    """Spread of p99 across the grid for each (scenario, system): how much the configuration matters."""
    rows = []
    for (sc, sysname), g in cells.groupby(["scenario", "system"]):
        v = g["p99"].dropna()
        if len(v) < 2:
            continue
        rows.append({"scenario": sc, "system": sysname, "cells": len(v), "p99_min_ms": v.min(), "p99_max_ms": v.max(),
                     "max_over_min": v.max() / v.min() if v.min() > 0 else np.nan,
                     "best_cell": _cell_label(g.loc[v.idxmin()]), "worst_cell": _cell_label(g.loc[v.idxmax()])})
    return pd.DataFrame(rows)


def _cell_label(r) -> str:
    base = f"t={int(r['timeout_ms'])}ms r={int(r['retries'])}"
    return base if r["threshold"] is None or pd.isna(r["threshold"]) else f"{base} f={int(r['threshold'])}"


# ------------------------------------------------------------------------------------------ CPU

def cpu_table(df: pd.DataFrame, cell: Optional[Tuple[int, int, int]]) -> pd.DataFrame:
    """Mean CPU (percent of one core) per service, and CPU-seconds per 1000 successful requests, by system."""
    rows = []
    for sc in sorted(df["scenario"].unique()):
        for sysname in SYSTEM_ORDER:
            g = cell_runs(df, sc, sysname, cell) if cell else df[(df["scenario"] == sc) & (df["system"] == sysname)]
            if g.empty:
                continue
            ok = g["overall_count"] - g["overall_errors"]
            for svc in ("inventory", "payment", "order"):
                mean, lo, hi, n = mean_ci(g[f"cpu_pct_{svc}"])
                per_k = g[f"cpu_seconds_{svc}"] / ok.replace(0, np.nan) * 1000
                m2, lo2, hi2, _ = mean_ci(per_k)
                rows.append({"scenario": sc, "system": sysname, "service": svc, "n_runs": n, "cpu_pct": mean,
                             "cpu_pct_lo": lo, "cpu_pct_hi": hi, "cpu_s_per_1k_ok": m2, "cpu_s_per_1k_lo": lo2,
                             "cpu_s_per_1k_hi": hi2, "cpu_source": g[f"cpu_source_{svc}"].iloc[0]})
    return pd.DataFrame(rows)


def breaker_activity(df: pd.DataFrame, cell: Tuple[int, int, int]) -> pd.DataFrame:
    """How often breakers opened / adapted, per scenario and system (explains the latency numbers)."""
    rows = []
    for sc in sorted(df["scenario"].unique()):
        for sysname in ("static", "adaptive"):
            g = cell_runs(df, sc, sysname, cell)
            if g.empty:
                continue
            rows.append({"scenario": sc, "system": sysname, "n_runs": len(g),
                         "payment_opens": g["cb_opens_payment"].mean(), "payment_open_s": g["cb_open_s_payment"].mean(),
                         "inventory_opens": g["cb_opens_inventory"].mean(),
                         "inventory_open_s": g["cb_open_s_inventory"].mean(),
                         "tighten_events": g["adapt_tighten_payment"].mean() + g["adapt_tighten_inventory"].mean(),
                         "relax_events": g["adapt_relax_payment"].mean() + g["adapt_relax_inventory"].mean()})
    return pd.DataFrame(rows)


def md_table(df: pd.DataFrame, columns: List[str], headers: Optional[List[str]] = None, floatfmt: str = "{:.1f}") -> str:
    """Minimal GitHub-markdown table (avoids a dependency on tabulate)."""
    headers = headers or columns
    lines = ["| " + " | ".join(headers) + " |", "|" + "|".join("---" for _ in columns) + "|"]
    for _, r in df.iterrows():
        cells = []
        for c in columns:
            v = r[c]
            if isinstance(v, (float, np.floating)):
                cells.append("-" if np.isnan(v) else floatfmt.format(v))
            else:
                cells.append("-" if v is None or (isinstance(v, float) and np.isnan(v)) else str(v))
        lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines)
