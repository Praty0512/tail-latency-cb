"""Sweep planning: grid generation, config ids and the (interleaved, seeded) run order."""
from __future__ import annotations

import itertools
import random
from dataclasses import dataclass
from typing import Dict, Iterable, List, Sequence

# Full grid of the study (spec, phase 5).
FULL_TIMEOUTS_MS = (500, 1000, 2000, 3000)
FULL_RETRIES = (0, 1, 3)
FULL_THRESHOLDS = (3, 5, 10)

# Reduced grid for smoke tests (--quick).
QUICK_TIMEOUTS_MS = (1000, 2000)
QUICK_RETRIES = (0, 1)
QUICK_THRESHOLDS = (5,)

SYSTEMS = ("none", "static", "adaptive")

# The base paper's own Istio configuration (its Fig 5): 3 attempts, 2 s per-try timeout, 5 consecutive errors.
REFERENCE_CONFIG = {"timeout_ms": 2000, "retries": 3, "threshold": 5}


@dataclass(frozen=True)
class RunSpec:
    scenario: str
    system: str
    timeout_ms: int
    retries: int
    threshold: int | None  # None for the no-breaker system (the dimension does not exist there)
    repeat: int            # 1-based run index k
    seed: int

    @property
    def config_id(self) -> str:
        return config_id(self.scenario, self.system, self.timeout_ms, self.retries, self.threshold)


def config_id(scenario: str, system: str, timeout_ms: int, retries: int, threshold: int | None) -> str:
    """``<scenario>__<system>__t<timeout>_r<retries>[_f<threshold>]``.

    The scenario is part of the id because results are stored at ``results/<config_id>/run_k.csv`` and the same
    grid cell is run under every scenario. The no-breaker system has no threshold, so it is run once per
    (timeout, retries) cell instead of once per threshold.
    """
    base = f"{scenario}__{system}__t{timeout_ms}_r{retries}"
    return base if threshold is None else f"{base}_f{threshold}"


def parse_config_id(cid: str) -> Dict[str, object]:
    scenario, system, cell = cid.split("__")
    parts = cell.split("_")
    out: Dict[str, object] = {"scenario": scenario, "system": system, "threshold": None}
    for p in parts:
        if p.startswith("t"):
            out["timeout_ms"] = int(p[1:])
        elif p.startswith("r"):
            out["retries"] = int(p[1:])
        elif p.startswith("f"):
            out["threshold"] = int(p[1:])
    return out


def grid_cells(system: str, timeouts: Sequence[int], retries: Sequence[int], thresholds: Sequence[int]):
    """Grid cells for one system: (timeout, retries, threshold-or-None)."""
    if system == "none":
        return [(t, r, None) for t, r in itertools.product(timeouts, retries)]
    return [(t, r, f) for t, r, f in itertools.product(timeouts, retries, thresholds)]


def plan_runs(scenarios: Iterable[str], systems: Iterable[str], timeouts: Sequence[int], retries: Sequence[int],
              thresholds: Sequence[int], repeats: int, base_seed: int) -> List[RunSpec]:
    """All runs, ordered *repeat-major* with a seeded shuffle inside each repeat.

    Interleaving the configurations (instead of running all repeats of one configuration back to back) spreads
    slow drifts of the machine (thermal state, background load) over the configurations rather than confounding
    them with one configuration. All configurations of repeat k share the seed ``base_seed + k``, so within a
    repeat the same requests are faulted in every system (a paired design).
    """
    scenarios, systems = list(scenarios), list(systems)
    cells = [(s, sys_, t, r, f) for s in scenarios for sys_ in systems
             for (t, r, f) in grid_cells(sys_, timeouts, retries, thresholds)]
    plan: List[RunSpec] = []
    for k in range(1, repeats + 1):
        order = list(cells)
        random.Random(base_seed * 7919 + k).shuffle(order)
        for (s, sys_, t, r, f) in order:
            plan.append(RunSpec(s, sys_, t, r, f, k, base_seed + k))
    return plan


def estimate_hours(n_runs: int, duration_s: float, warmup_s: float, overhead_s: float = 12.0) -> float:
    return n_runs * (duration_s + warmup_s + overhead_s) / 3600.0
