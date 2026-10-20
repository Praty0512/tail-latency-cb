"""Unit tests for the experiment harness logic that needs no running services."""
import numpy as np

from harness import sweep
from harness.loadgen import interval_rows, is_error, summarize
from harness.run_experiments import build_parser, resolve
from harness.scenarios import load_scenarios


def test_full_grid_matches_the_specification():
    cells = sweep.grid_cells("static", sweep.FULL_TIMEOUTS_MS, sweep.FULL_RETRIES, sweep.FULL_THRESHOLDS)
    assert len(cells) == 4 * 3 * 3 == 36
    assert len(set(cells)) == 36
    assert {c[0] for c in cells} == {500, 1000, 2000, 3000}
    assert {c[1] for c in cells} == {0, 1, 3}
    assert {c[2] for c in cells} == {3, 5, 10}


def test_no_breaker_system_has_no_threshold_dimension():
    cells = sweep.grid_cells("none", sweep.FULL_TIMEOUTS_MS, sweep.FULL_RETRIES, sweep.FULL_THRESHOLDS)
    assert len(cells) == 12
    assert all(c[2] is None for c in cells)


def test_plan_size_for_the_full_study():
    names = ["baseline", "delay", "errors", "mixed", "coldstart"]
    plan = sweep.plan_runs(names, sweep.SYSTEMS, sweep.FULL_TIMEOUTS_MS, sweep.FULL_RETRIES,
                           sweep.FULL_THRESHOLDS, repeats=5, base_seed=42)
    # (12 + 36 + 36) cells x 5 scenarios x 5 repeats
    assert len(plan) == 84 * 5 * 5 == 2100
    one_scenario = sweep.plan_runs(["mixed"], sweep.SYSTEMS, sweep.FULL_TIMEOUTS_MS, sweep.FULL_RETRIES,
                                   sweep.FULL_THRESHOLDS, 5, 42)
    assert len(one_scenario) == 420


def test_config_id_round_trip_and_uniqueness():
    plan = sweep.plan_runs(["delay", "mixed"], sweep.SYSTEMS, sweep.FULL_TIMEOUTS_MS, sweep.FULL_RETRIES,
                           sweep.FULL_THRESHOLDS, 2, 1)
    ids = {(r.config_id, r.repeat) for r in plan}
    assert len(ids) == len(plan)  # no collisions
    for r in plan:
        d = sweep.parse_config_id(r.config_id)
        assert (d["scenario"], d["system"], d["timeout_ms"], d["retries"], d["threshold"]) == \
               (r.scenario, r.system, r.timeout_ms, r.retries, r.threshold)
    assert sweep.config_id("mixed", "static", 2000, 3, 5) == "mixed__static__t2000_r3_f5"
    assert sweep.config_id("mixed", "none", 2000, 3, None) == "mixed__none__t2000_r3"


def test_plan_is_repeat_major_shuffled_and_reproducible():
    args = (["mixed"], sweep.SYSTEMS, (1000, 2000), (0, 1), (5,), 3, 7)
    a, b = sweep.plan_runs(*args), sweep.plan_runs(*args)
    assert a == b
    n = len(a) // 3
    assert [r.repeat for r in a] == [1] * n + [2] * n + [3] * n
    # every repeat covers the same cells, in different orders
    assert {r.config_id for r in a[:n]} == {r.config_id for r in a[n:2 * n]}
    assert [r.config_id for r in a[:n]] != [r.config_id for r in a[n:2 * n]]
    # all systems in a repeat share one seed (paired design); seeds differ between repeats
    assert len({r.seed for r in a[:n]}) == 1
    assert len({r.seed for r in a}) == 3


def test_quick_preset_and_explicit_overrides():
    args = resolve(build_parser().parse_args(["--quick"]))
    assert args.repeats == 1 and args.duration == 45 and args.warmup == 10
    assert list(args.timeouts) == list(sweep.QUICK_TIMEOUTS_MS)
    args = resolve(build_parser().parse_args(["--quick", "--repeats", "3", "--duration", "60"]))
    assert args.repeats == 3 and args.duration == 60
    args = resolve(build_parser().parse_args([]))
    assert args.repeats == 5 and args.duration == 300 and args.users == 290 and args.ramp_rate == 100


def test_scenarios_define_the_five_required_cases():
    sc = load_scenarios()
    assert set(sc) == {"baseline", "delay", "errors", "mixed", "coldstart"}
    assert sc["baseline"].faults == {} and sc["baseline"].events == []
    d = sc["delay"].fault_specs(300, seed=9, origin_epoch_ms=1000)
    rule = d["payment-service"]["rules"][0]
    assert rule["type"] == "DELAY" and rule["delayMs"] == 3000 and rule["probability"] == 0.5
    assert (rule["startOffsetMs"], rule["endOffsetMs"]) == (60_000, 180_000)  # t = 60 s .. 180 s
    assert d["payment-service"]["seed"] == 9 and d["payment-service"]["originEpochMs"] == 1000
    e = sc["errors"].fault_specs(300, 1, 0)["payment-service"]["rules"][0]
    assert e["type"] == "ERROR" and e["status"] == 500 and e["probability"] == 0.3
    m = sc["mixed"].fault_specs(300, 1, 0)
    assert set(m) == {"payment-service", "inventory-service"}
    assert sc["coldstart"].timed_events(300) == [{"at_s": 120.0, "action": "restart", "service": "payment-service"}]


def test_fault_windows_scale_with_duration():
    sc = load_scenarios()["delay"]
    r = sc.fault_specs(45, 1, 0)["payment-service"]["rules"][0]
    assert (r["startOffsetMs"], r["endOffsetMs"]) == (9000, 27000)
    assert sc.fault_window_frac() == (0.2, 0.6)


def test_error_classification():
    assert is_error(500) and is_error(503) and is_error(0) and is_error(-1)
    assert not is_error(201) and not is_error(409) and not is_error(402)


def test_summarize_uses_exact_percentiles_and_separates_success_only():
    lat = np.array([float(i) for i in range(1, 101)])
    err = np.zeros(100, dtype=bool)
    err[99] = True  # the slowest request failed
    s = summarize(lat, err, duration_s=10)
    assert s["count"] == 100 and s["errors"] == 1
    assert abs(s["p99"] - np.percentile(lat, 99)) < 1e-9
    assert abs(s["okP99"] - np.percentile(lat[:99], 99)) < 1e-9
    assert s["throughputRps"] == 10 and s["goodputRps"] == 9.9


def test_interval_rows_do_not_average_percentiles():
    t = np.concatenate([np.full(95, 1.0), np.full(5, 7.0)])
    lat = np.concatenate([np.full(95, 10.0), np.full(5, 1000.0)])
    err = np.zeros(100, dtype=bool)
    rows = interval_rows(t, lat, err, interval_s=5, duration_s=10)
    assert [r["count"] for r in rows] == [95, 5]
    assert rows[0]["p99"] == 10.0 and rows[1]["p99"] == 1000.0
