"""Tests of the analysis code on *synthetic* run files with planted effects.

The synthetic data exists only inside pytest's temporary directory to check that the pipeline recovers known
effects; it is never written to the results directory and never appears in any reported number.
"""
import json
import math

import numpy as np
import pandas as pd
import pytest

from analysis import figures, stats, tables
from analysis.loading import load_runs
from analysis.run_analysis import build

# planted means (ms) per system for the metrics avg / p95 / p99 in the fault window
PLANTED = {"none": (100.0, 800.0, 1000.0), "static": (70.0, 700.0, 800.0), "adaptive": (60.0, 400.0, 500.0)}
ERR = {"none": 0.30, "static": 0.40, "adaptive": 0.35}


def _phase(avg, p95, p99, err, n=1000):
    return {"count": n, "errors": int(n * err), "errorRate": err, "throughputRps": n / 120, "goodputRps": n * (1 - err) / 120,
            "avg": avg, "p50": avg / 2, "p95": p95, "p99": p99, "okAvg": avg * 0.8, "okP95": p95 * 0.8, "okP99": p99 * 0.8}


def _svc(cpu_s, transitions=None, resilience=None):
    d = {"meta": {"startEpochMs": 0, "endEpochMs": 300_000, "cpuSeconds": cpu_s, "cpuSource": "jmx"},
         "summary": [{"direction": "inbound", "endpoint": "x", "p99": 12.0}], "intervals": []}
    if transitions is not None:
        d["cbTransitions"] = transitions
    if resilience is not None:
        d["resilience"] = resilience
    return d


def _write_run(root, scenario, system, t, r, f, k, rng, planted=PLANTED, duration=300):
    cid = f"{scenario}__{system}__t{t}_r{r}" + (f"_f{f}" if f is not None else "")
    d = root / cid
    d.mkdir(parents=True, exist_ok=True)
    avg, p95, p99 = (v * (1 + rng.normal(0, 0.01)) for v in planted[system])
    trans = [{"epochMs": 1, "tSec": 70.0, "breaker": "payment-service", "from": "CLOSED", "to": "OPEN", "reason": "x"},
             {"epochMs": 2, "tSec": 80.0, "breaker": "payment-service", "from": "OPEN", "to": "HALF_OPEN", "reason": "x"}]
    res = {"targets": {"payment-service": {"baselineThreshold": 5, "adaptationEvents": [
        {"action": "TIGHTEN", "tSec": 65.0}, {"action": "RELAX", "tSec": 200.0}],
        "evaluationTrace": [{"tSec": 5.0, "threshold": 5}, {"tSec": 65.0, "threshold": 2}, {"tSec": 200.0, "threshold": 3}]}}}
    j = {"meta": {"configId": cid, "scenario": scenario, "system": system, "timeoutMs": t, "retries": r,
                  "failureThreshold": f, "run": k, "seed": 42 + k, "durationS": duration, "warmupS": 15,
                  "faultWindowS": [60.0, 180.0], "load": {"users": 290}},
         "e2e": {"overall": _phase(avg * 0.6, p95 * 0.9, p99 * 0.9, ERR[system] * 0.5),
                 "fault": _phase(avg, p95, p99, ERR[system])},
         "services": {"inventory-service": _svc(10.0), "payment-service": _svc(12.0),
                      "order-service": _svc(30.0 if system != "adaptive" else 33.0, trans, res)}}
    (d / f"run_{k}.json").write_text(json.dumps(j), encoding="utf-8")
    lat = rng.exponential(50, 400)
    pd.DataFrame({"t_s": np.linspace(0, duration - 1, 400), "latency_ms": lat, "status": 201, "ok": 1,
                  "phase": "measured", "user": 0, "key": "k"}).to_csv(d / f"run_{k}.requests.csv", index=False)


@pytest.fixture()
def synthetic(tmp_path):
    rng = np.random.default_rng(1)
    for k in range(1, 6):
        for system in ("none", "static", "adaptive"):
            for t in (1000, 2000):
                for r in (0, 3):
                    for f in ((None,) if system == "none" else (3, 5)):
                        _write_run(tmp_path, "delay", system, t, r, f, k, rng)
    return tmp_path


def test_loader_extracts_phases_cpu_and_breaker_activity(synthetic):
    df = load_runs(synthetic)
    assert len(df) == 5 * (4 + 8 + 8)
    row = df[(df.system == "adaptive") & (df.timeout_ms == 2000) & (df.retries == 3) & (df.threshold == 5) & (df.run == 1)].iloc[0]
    assert row["fault_count"] == 1000 and 55 < row["fault_avg"] < 65
    assert row["cpu_pct_order"] == pytest.approx(100 * 33 / 300)
    assert row["cb_opens_payment"] == 1 and row["cb_open_s_payment"] == pytest.approx(10.0)
    assert row["adapt_tighten_payment"] == 1 and row["adapt_relax_payment"] == 1


def test_claim_table_recovers_planted_gains(synthetic):
    df = load_runs(synthetic)
    t = tables.claim_table(df, (2000, 3, 5), "fault")
    g = t[(t.system == "static") & (t.metric == "avg")].iloc[0]
    assert g["gain_pct"] == pytest.approx(30.0, abs=1.5)          # planted: 100 -> 70
    assert g["ci_lo"] < g["gain_pct"] < g["ci_hi"]
    p99 = t[(t.system == "adaptive") & (t.metric == "p99")].iloc[0]
    assert p99["gain_pct"] == pytest.approx(50.0, abs=1.5)        # planted: 1000 -> 500
    assert p99["verdict"].startswith("holds")                     # whole CI above 15 %
    assert p99["mw_p"] < 0.05                                      # n=5 vs 5, perfectly separated
    st99 = t[(t.system == "static") & (t.metric == "p99")].iloc[0]
    assert st99["gain_pct"] == pytest.approx(20.0, abs=1.5)
    err = t[(t.system == "static") & (t.metric == "error rate (pp change)")].iloc[0]
    assert err["gain_pct"] == pytest.approx(10.0, abs=0.5)         # 30 % -> 40 % errors


def test_matched_gains_cover_the_grid_and_flag_sign(synthetic):
    df = load_runs(synthetic)
    cells = tables.cell_means(df, "fault")
    gains = tables.matched_gains(cells)
    assert len(gains) == 16                                        # 2 timeouts x 2 retries x 2 thresholds x 2 systems
    s = tables.grid_gain_summary(gains, 15.0)
    row = s[(s.system == "adaptive") & (s.metric == "p99")].iloc[0]
    assert row["cells"] == 8 and row["cells_ge_threshold"] == 8 and row["cells_worse_than_none"] == 0


def test_pick_reference_falls_back_when_the_papers_cell_is_missing(synthetic):
    df = load_runs(synthetic)
    assert tables.pick_reference(df) == ((2000, 3, 5), True)          # the base paper's Fig 5 cell is present
    reduced = df[df["threshold"] != 5]                                  # drop threshold 5 (keeps the NaN of "none")
    cell, is_requested = tables.pick_reference(reduced)
    assert is_requested is False and cell[2] == 3
    assert not tables.cell_runs(reduced, "delay", "static", cell).empty


def test_statistics_helpers_on_known_values():
    m, lo, hi, n = stats.mean_ci([10, 12, 14, 16, 18])
    assert m == 14 and n == 5 and lo < 14 < hi
    assert hi - m == pytest.approx(2.776 * np.std([10, 12, 14, 16, 18], ddof=1) / math.sqrt(5), rel=1e-3)
    assert math.isnan(stats.mean_ci([5.0])[1])
    assert stats.mannwhitney_p([1, 2, 3, 4, 5], [6, 7, 8, 9, 10]) == pytest.approx(2 / 252, rel=1e-6)
    assert stats.mannwhitney_p([1, 1, 1], [1, 1, 1]) == 1.0
    assert stats.improvement_pct(100, 85) == pytest.approx(15)
    point, lo, hi = stats.bootstrap_improvement_ci([100, 101, 99, 100], [50, 51, 49, 50])
    assert point == pytest.approx(50, abs=0.5) and 45 < lo < point < hi < 55
    assert all(math.isnan(x) for x in stats.bootstrap_improvement_ci([1, 2], [1, 2])[1:])
    assert stats.cliffs_delta([5, 6, 7], [1, 2, 3]) == 1.0


def test_holm_adjustment():
    adj = stats.holm([0.01, 0.04, 0.03, float("nan")])
    assert adj[0] == pytest.approx(0.03)      # 0.01 * 3
    assert adj[2] == pytest.approx(0.06)      # 0.03 * 2
    assert adj[1] == pytest.approx(0.06)      # monotone: max(0.06, 0.04*1)
    assert math.isnan(adj[3])


def test_verdict_uses_the_whole_interval():
    assert stats.verdict(16, 30, 23).startswith("holds")
    assert stats.verdict(2, 12, 7).startswith("does not hold")
    assert stats.verdict(5, 25, 15).startswith("inconclusive")
    assert stats.verdict(float("nan"), float("nan"), 10) == "insufficient repeats"


def test_full_pipeline_writes_summary_tables_and_figures(synthetic, tmp_path):
    out = tmp_path / "out"
    assert build(synthetic, out, 15.0, label="SYNTHETIC TEST") == 0
    md = (out / "summary.md").read_text(encoding="utf-8")
    for heading in ("## 1.", "## 2.", "## 3.", "## 4.", "## 5.", "Method notes"):
        assert heading in md
    assert "SYNTHETIC TEST" in md
    assert (out / "tables" / "claim_check.csv").exists() and (out / "tables" / "runs.csv").exists()
    pngs = {p.name for p in (out / "figures").glob("*.png")}
    assert "heatmap_p99_delay_fault.png" in pngs and "cpu_per_service.png" in pngs
    assert "gain_strip_fault.png" in pngs and "timeseries_delay.png" in pngs
    assert all(p.stat().st_size > 5_000 for p in (out / "figures").glob("*.png"))


def test_empty_results_directory_reports_no_data(tmp_path, capsys):
    assert build(tmp_path, tmp_path / "o") == 0
    assert "have not been run yet" in capsys.readouterr().out
    assert not (tmp_path / "o").exists()


def test_figure_palette_is_the_validated_slot_order():
    assert list(figures.SYS_COLOR.values()) == ["#2a78d6", "#eb6834", "#1baf7a"]


def test_coldstart_windows_use_the_logged_restart_event(tmp_path):
    rng = np.random.default_rng(3)
    for system in ("none", "static", "adaptive"):
        for k in (1, 2, 3):
            _write_run(tmp_path, "coldstart", system, 2000, 1, None if system == "none" else 5, k, rng, duration=120)
            d = tmp_path / (f"coldstart__{system}__t2000_r1" + ("" if system == "none" else "_f5"))
            j = json.loads((d / f"run_{k}.json").read_text())
            j["meta"]["events"] = [{"action": "restart", "service": "payment-service", "at_s": 48.0,
                                    "healthy_again_at_s": 58.0, "waited_for_health_s": 9.0}]
            (d / f"run_{k}.json").write_text(json.dumps(j))
            t = np.linspace(0, 119.9, 1200)
            lat = np.where((t >= 48) & (t < 58), 900.0, np.where((t >= 58) & (t < 88), 300.0, 20.0))
            pd.DataFrame({"t_s": t, "latency_ms": lat, "status": 201, "ok": np.where(lat == 900.0, 0, 1),
                          "phase": "measured", "user": 0, "key": "k"}).to_csv(d / f"run_{k}.requests.csv", index=False)
    from analysis.run_analysis import coldstart_table
    df = load_runs(tmp_path)
    t = coldstart_table(tmp_path, df, (2000, 1, 5))
    row = lambda w: t[(t.system == "static") & (t.window.str.startswith(w))].iloc[0]
    assert row("outage")["p99"].startswith("900")
    assert row("recovery")["avg"].startswith("300")
    assert row("steady")["avg"].startswith("20")
    assert row("outage")["err"].startswith("100")
    assert set(t["system"]) == {"none", "static", "adaptive"}
