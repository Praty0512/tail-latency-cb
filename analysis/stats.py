"""Small, dependency-light statistics used by the analysis.

All inputs are *repeat-level* values (one number per run), never per-request or per-interval numbers, so the
unit of replication is the run, which is what the confidence intervals and tests are about.
"""
from __future__ import annotations

from typing import Optional, Sequence, Tuple

import numpy as np
from scipy import stats as sps


def _clean(x: Sequence[float]) -> np.ndarray:
    a = np.asarray(list(x), dtype=float)
    return a[~np.isnan(a)]


def mean_ci(x: Sequence[float], conf: float = 0.95) -> Tuple[float, float, float, int]:
    """Mean and Student-t confidence interval across repeats. Returns (mean, lo, hi, n); lo/hi are NaN if n < 2."""
    a = _clean(x)
    n = len(a)
    if n == 0:
        return float("nan"), float("nan"), float("nan"), 0
    m = float(a.mean())
    if n < 2:
        return m, float("nan"), float("nan"), n
    half = float(sps.t.ppf(0.5 + conf / 2, n - 1) * a.std(ddof=1) / np.sqrt(n))
    return m, m - half, m + half, n


def improvement_pct(base_mean: float, other_mean: float) -> float:
    """Relative reduction of ``other`` versus ``base`` in percent (positive = ``other`` is lower = better)."""
    if base_mean is None or other_mean is None or np.isnan(base_mean) or np.isnan(other_mean) or base_mean == 0:
        return float("nan")
    return (base_mean - other_mean) / base_mean * 100.0


def bootstrap_improvement_ci(base: Sequence[float], other: Sequence[float], conf: float = 0.95,
                             n_boot: int = 10_000, seed: int = 0) -> Tuple[float, float, float]:
    """Percentile-bootstrap CI of ``(mean(base) - mean(other)) / mean(base) * 100``.

    Both groups are resampled independently with replacement (runs are the unit of resampling). Returns
    (point estimate, lo, hi); lo/hi are NaN unless each group has at least 3 runs, because a bootstrap of fewer
    than 3 values cannot represent the sampling variability.
    """
    a, b = _clean(base), _clean(other)
    point = improvement_pct(float(a.mean()) if len(a) else float("nan"), float(b.mean()) if len(b) else float("nan"))
    if len(a) < 3 or len(b) < 3:
        return point, float("nan"), float("nan")
    rng = np.random.default_rng(seed)
    ia = rng.integers(0, len(a), size=(n_boot, len(a)))
    ib = rng.integers(0, len(b), size=(n_boot, len(b)))
    ma, mb = a[ia].mean(axis=1), b[ib].mean(axis=1)
    with np.errstate(divide="ignore", invalid="ignore"):
        d = (ma - mb) / ma * 100.0
    d = d[np.isfinite(d)]
    if len(d) == 0:
        return point, float("nan"), float("nan")
    lo, hi = np.percentile(d, [(1 - conf) / 2 * 100, (1 + conf) / 2 * 100])
    return point, float(lo), float(hi)


def mannwhitney_p(a: Sequence[float], b: Sequence[float]) -> float:
    """Two-sided Mann-Whitney U p-value (exact for small samples without ties). NaN if a group has < 2 runs."""
    x, y = _clean(a), _clean(b)
    if len(x) < 2 or len(y) < 2:
        return float("nan")
    if np.ptp(np.concatenate([x, y])) == 0:
        return 1.0
    try:
        return float(sps.mannwhitneyu(x, y, alternative="two-sided").pvalue)
    except ValueError:
        return 1.0


def cliffs_delta(a: Sequence[float], b: Sequence[float]) -> float:
    """Cliff's delta in [-1, 1]: P(a > b) - P(a < b). Positive means ``a`` tends to be larger."""
    x, y = _clean(a), _clean(b)
    if len(x) == 0 or len(y) == 0:
        return float("nan")
    gt = sum((xi > y).sum() for xi in x)
    lt = sum((xi < y).sum() for xi in x)
    return float((gt - lt) / (len(x) * len(y)))


def holm(pvals: Sequence[float]) -> np.ndarray:
    """Holm-Bonferroni adjusted p-values (NaNs are ignored and returned as NaN)."""
    p = np.asarray(list(pvals), dtype=float)
    out = np.full_like(p, np.nan)
    idx = np.where(~np.isnan(p))[0]
    if len(idx) == 0:
        return out
    order = idx[np.argsort(p[idx])]
    m = len(order)
    running = 0.0
    for rank, i in enumerate(order):
        adj = (m - rank) * p[i]
        running = max(running, adj)
        out[i] = min(1.0, running)
    return out


def verdict(lo: float, hi: float, point: float, threshold: float = 15.0) -> str:
    """Classifies a measured gain (in %) against a claimed gain using its confidence interval."""
    if np.isnan(point):
        return "n/a"
    if np.isnan(lo) or np.isnan(hi):
        return "insufficient repeats"
    if lo >= threshold:
        return f"holds (CI above {threshold:g}%)"
    if hi < threshold:
        return f"does not hold (CI below {threshold:g}%)"
    return f"inconclusive (CI spans {threshold:g}%)"
