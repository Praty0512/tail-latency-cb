"""Scenario definitions (harness/scenarios.yaml) turned into concrete fault specs and events for one run."""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List

import yaml

DEFAULT_FILE = Path(__file__).with_name("scenarios.yaml")


@dataclass
class Scenario:
    name: str
    description: str
    faults: Dict[str, List[dict]] = field(default_factory=dict)  # service -> rules (with startFrac/endFrac)
    events: List[dict] = field(default_factory=list)

    def fault_window_frac(self):
        """(start, end) fraction of the run during which any fault is active, or None."""
        starts, ends = [], []
        for rules in self.faults.values():
            for r in rules:
                starts.append(r.get("startFrac", 0.0))
                ends.append(r.get("endFrac", 1.0))
        if not starts:
            return None
        return min(starts), max(ends)

    def fault_specs(self, duration_s: float, seed: int, origin_epoch_ms: int) -> Dict[str, dict]:
        """Concrete POST /admin/fault bodies per service; windows scaled to ``duration_s``."""
        specs: Dict[str, dict] = {}
        for service, rules in self.faults.items():
            out = []
            for r in rules:
                r = dict(r)
                start = r.pop("startFrac", None)
                end = r.pop("endFrac", None)
                if start is not None:
                    r["startOffsetMs"] = int(round(start * duration_s * 1000))
                if end is not None:
                    r["endOffsetMs"] = int(round(end * duration_s * 1000))
                out.append(r)
            specs[service] = {"enabled": True, "seed": seed, "originEpochMs": origin_epoch_ms, "rules": out}
        return specs

    def timed_events(self, duration_s: float) -> List[dict]:
        evs = []
        for e in self.events:
            evs.append({"at_s": e["at_frac"] * duration_s, "action": e["action"], "service": e["service"]})
        return sorted(evs, key=lambda e: e["at_s"])


def load_scenarios(path: Path = DEFAULT_FILE) -> Dict[str, Scenario]:
    raw = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    out: Dict[str, Scenario] = {}
    for name, body in raw.items():
        out[name] = Scenario(name=name, description=body.get("description", ""),
                             faults=body.get("faults") or {}, events=body.get("events") or [])
    return out
