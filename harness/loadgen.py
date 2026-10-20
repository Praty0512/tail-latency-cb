"""Closed-loop load generator (Locust-style virtual users) built on asyncio/aiohttp.

Why not Locust itself: the experiment needs (a) deterministic, seeded request keys and think times so that the
set of requests hit by a fault is identical across systems, (b) in-process phase control (warm-up -> measured
run, fault windows and service restarts at exact offsets) and (c) one raw record per request. All of that is a
few dozen lines on asyncio; Locust's gevent runtime would only add moving parts.

Load model: ``users`` virtual users, started at ``ramp_rate`` users/second, each looping
"think (uniform think_min..think_max) -> POST /api/orders". With the defaults (290 users, 1-3 s think time)
that is ~145 requests/s, comparable to the 90-150 req/s the base paper reports. This is a *closed* loop, like
Locust: a stalled system slows its own users down, so a latency spike is under-sampled relative to an open-loop
(constant arrival rate) generator (the "coordinated omission" caveat, discussed in the README).
"""
from __future__ import annotations

import asyncio
import random
import time
from dataclasses import dataclass, field
from typing import List, Optional, Tuple

import aiohttp
import numpy as np


@dataclass
class LoadConfig:
    url: str = "http://localhost:8080/api/orders"
    users: int = 290              # concurrent users (base paper: 290)
    ramp_rate: float = 100.0      # users spawned per second (base paper: 100)
    think_min: float = 1.0
    think_max: float = 3.0
    client_timeout_s: float = 60.0
    seed: int = 1
    sku_count: int = 5000

    @property
    def ramp_time_s(self) -> float:
        return self.users / self.ramp_rate


# One request record: (start epoch seconds, latency ms, http status or 0 = timeout / -1 = connection error, user id, key)
Record = Tuple[float, float, int, int, str]


@dataclass
class LoadState:
    records: List[Record] = field(default_factory=list)
    stop: asyncio.Event = field(default_factory=asyncio.Event)
    started_epoch: float = 0.0


async def _user(uid: int, cfg: LoadConfig, session: aiohttp.ClientSession, state: LoadState) -> None:
    rnd = random.Random(f"{cfg.seed}:{uid}")
    await asyncio.sleep(uid / cfg.ramp_rate)  # ramp-up: user i starts i/ramp_rate seconds in
    n = 0
    while not state.stop.is_set():
        key = f"s{cfg.seed}-u{uid}-n{n}"
        n += 1
        body = {"sku": f"SKU-{rnd.randrange(1, cfg.sku_count + 1)}", "quantity": rnd.randint(1, 3),
                "amount": round(rnd.uniform(5, 500), 2), "cardLast4": "4242"}
        think = rnd.uniform(cfg.think_min, cfg.think_max)
        start_epoch = time.time()
        t0 = time.perf_counter()
        status = -1
        try:
            async with session.post(cfg.url, json=body, headers={"X-Request-Key": key}) as r:
                await r.read()
                status = r.status
        except asyncio.TimeoutError:
            status = 0
        except (aiohttp.ClientError, OSError):
            status = -1
        latency_ms = (time.perf_counter() - t0) * 1000.0
        state.records.append((start_epoch, latency_ms, status, uid, key))
        try:
            await asyncio.wait_for(state.stop.wait(), timeout=think)
        except asyncio.TimeoutError:
            pass


class LoadGenerator:
    """Runs the virtual users as background tasks; ``stop()`` ends them and returns all records."""

    def __init__(self, cfg: LoadConfig):
        self.cfg = cfg
        self.state = LoadState()
        self._tasks: List[asyncio.Task] = []
        self._session: Optional[aiohttp.ClientSession] = None

    async def start(self) -> None:
        connector = aiohttp.TCPConnector(limit=0, ttl_dns_cache=300)
        self._session = aiohttp.ClientSession(connector=connector,
                                              timeout=aiohttp.ClientTimeout(total=self.cfg.client_timeout_s))
        self.state.started_epoch = time.time()
        self._tasks = [asyncio.create_task(_user(i, self.cfg, self._session, self.state)) for i in range(self.cfg.users)]

    async def stop(self, drain_s: float = 90.0) -> List[Record]:
        self.state.stop.set()
        if self._tasks:
            done, pending = await asyncio.wait(self._tasks, timeout=drain_s)
            for p in pending:
                p.cancel()
        if self._session:
            await self._session.close()
        return self.state.records


# ---------------------------------------------------------------------------------------------- statistics

def is_error(status: int) -> bool:
    """A request counts as failed if it timed out, could not connect or got a 5xx. 4xx are business outcomes."""
    return status <= 0 or status >= 500


def summarize(latencies_ms: np.ndarray, errors: np.ndarray, duration_s: float) -> dict:
    """Run-level statistics of raw per-request data (exact percentiles, never averages of interval percentiles)."""
    n = int(len(latencies_ms))
    if n == 0:
        return {"count": 0}
    ok = latencies_ms[~errors]

    def pct(a, p):
        return float(np.percentile(a, p)) if len(a) else None

    return {
        "count": n,
        "errors": int(errors.sum()),
        "errorRate": float(errors.mean()),
        "throughputRps": n / duration_s if duration_s > 0 else None,
        "goodputRps": len(ok) / duration_s if duration_s > 0 else None,
        "avg": float(latencies_ms.mean()),
        "p50": pct(latencies_ms, 50), "p90": pct(latencies_ms, 90), "p95": pct(latencies_ms, 95),
        "p99": pct(latencies_ms, 99), "p999": pct(latencies_ms, 99.9), "max": float(latencies_ms.max()),
        "okCount": int(len(ok)),
        "okAvg": float(ok.mean()) if len(ok) else None,
        "okP50": pct(ok, 50), "okP95": pct(ok, 95), "okP99": pct(ok, 99),
    }


def interval_rows(t_rel: np.ndarray, latencies_ms: np.ndarray, errors: np.ndarray, interval_s: float,
                  duration_s: float):
    """Per-interval statistics for the CSV (one dict per interval, keyed by the interval end in seconds)."""
    rows = []
    k = int(np.ceil(duration_s / interval_s))
    for i in range(k):
        lo, hi = i * interval_s, (i + 1) * interval_s
        m = (t_rel >= lo) & (t_rel < hi)
        lat, err = latencies_ms[m], errors[m]
        n = int(m.sum())
        rows.append({
            "t_end_s": min(hi, duration_s), "count": n, "errors": int(err.sum()),
            "p50": float(np.percentile(lat, 50)) if n else None,
            "p95": float(np.percentile(lat, 95)) if n else None,
            "p99": float(np.percentile(lat, 99)) if n else None,
            "avg": float(lat.mean()) if n else None,
            "error_rate": float(err.mean()) if n else None,
        })
    return rows
