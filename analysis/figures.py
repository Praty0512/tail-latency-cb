"""Figures (PNG). Styling follows the data-viz method: fixed categorical slots per system, one-hue sequential
ramp for magnitude, blue<->red diverging pair around a neutral midpoint for gains, thin marks, recessive grid,
text in ink colours (never in series colours), legend always present, values annotated in the heatmaps so no
information depends on colour alone."""
from __future__ import annotations

from pathlib import Path
from typing import Dict, Optional, Tuple

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from matplotlib.colors import LinearSegmentedColormap, LogNorm, TwoSlopeNorm  # noqa: E402
from matplotlib.lines import Line2D  # noqa: E402
from matplotlib.ticker import FuncFormatter, NullFormatter  # noqa: E402

SURFACE, INK, INK2, MUTED, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#898781", "#e1e0d9"
# categorical slots 1-3 (validated all-pairs: CVD dE >= 9.2, normal-vision dE >= 24); colour follows the system
SYS_COLOR = {"none": "#2a78d6", "static": "#eb6834", "adaptive": "#1baf7a"}
SYS_MARK = {"none": "o", "static": "s", "adaptive": "^"}      # secondary encoding (aqua is < 3:1 on this surface)
SYS_LABEL = {"none": "no breaker", "static": "static breaker", "adaptive": "adaptive breaker"}
SEQ = ["#cde2fb", "#9ec5f4", "#6da7ec", "#3987e5", "#256abf", "#184f95", "#0d366b"]   # blue 100..700
DIV_LOW, DIV_MID, DIV_HIGH = "#d03b3b", "#f0efec", "#2a78d6"                           # red - gray - blue
SEQ_CMAP = LinearSegmentedColormap.from_list("blue_seq", SEQ)
DIV_CMAP = LinearSegmentedColormap.from_list("red_blue", [DIV_LOW, "#e8a09f", DIV_MID, "#86b6ef", DIV_HIGH])


def _style(ax):
    ax.set_facecolor(SURFACE)
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    for s in ("left", "bottom"):
        ax.spines[s].set_color("#c3c2b7")
    ax.tick_params(colors=INK2, labelsize=8, length=3)
    ax.grid(axis="y", color=GRID, linewidth=0.6)
    ax.set_axisbelow(True)
    ax.xaxis.label.set_color(INK2)
    ax.yaxis.label.set_color(INK2)


def _fig(w, h):
    return plt.figure(figsize=(w, h), facecolor=SURFACE, constrained_layout=True)


def _plain_log_axis(axis, lo, hi):
    """Log axis with ticks written as plain numbers (10, 20, 50, ...) instead of 10^x mathtext."""
    nice = [v for v in (1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000) if lo * 0.8 <= v <= hi * 1.25]
    axis.set_ticks(nice)
    axis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:g}"))
    axis.set_minor_formatter(NullFormatter())


def _save(fig, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path, dpi=150, facecolor=SURFACE, bbox_inches="tight")
    plt.close(fig)
    return path


def _text_color(norm_value: float) -> str:
    return "#ffffff" if norm_value > 0.55 else INK


def _heat(ax, mat, xlabels, ylabels, cmap, norm, fmt, title=None):
    ax.set_facecolor(SURFACE)
    ax.imshow(mat, cmap=cmap, norm=norm, aspect="auto", origin="upper")
    ax.set_xticks(range(len(xlabels)))
    ax.set_xticklabels(xlabels, fontsize=8, color=INK2)
    ax.set_yticks(range(len(ylabels)))
    ax.set_yticklabels(ylabels, fontsize=8, color=INK2)
    for s in ax.spines.values():
        s.set_visible(False)
    ax.tick_params(length=0)
    for i in range(mat.shape[0]):
        for j in range(mat.shape[1]):
            v = mat[i, j]
            if np.isnan(v):
                ax.text(j, i, "-", ha="center", va="center", color=MUTED, fontsize=8)
            else:
                nv = norm(v)
                nv = float(nv) if np.ndim(nv) == 0 else 0.0
                col = _text_color(nv) if cmap is SEQ_CMAP else ("#ffffff" if abs(nv - 0.5) * 2 > 0.62 else INK)
                ax.text(j, i, fmt(v), ha="center", va="center", color=col, fontsize=8)
    if title:
        ax.set_title(title, fontsize=9, color=INK, loc="left")


def _matrix(g: pd.DataFrame, timeouts, retries, value: str) -> np.ndarray:
    m = np.full((len(retries), len(timeouts)), np.nan)
    for i, r in enumerate(retries):
        for j, t in enumerate(timeouts):
            s = g[(g["retries"] == r) & (g["timeout_ms"] == t)]
            if len(s):
                m[i, j] = s[value].mean()
    return m


def sensitivity_map(cells: pd.DataFrame, scenario: str, out: Path, metric: str = "p99") -> Optional[Path]:
    """Configuration-sensitivity map: metric over timeout (x) x retries (y), one panel per threshold, one row per
    system. One shared logarithmic colour scale so panels are directly comparable."""
    g = cells[cells["scenario"] == scenario]
    if g.empty:
        return None
    timeouts = sorted(g["timeout_ms"].unique())
    retries = sorted(g["retries"].unique())
    thresholds = sorted(int(x) for x in g["threshold"].dropna().unique())
    systems = [s for s in ("none", "static", "adaptive") if s in set(g["system"])]
    cols = max(1, len(thresholds))
    vals = g[metric].dropna()
    if vals.empty:
        return None
    norm = LogNorm(vmin=max(1.0, vals.min()), vmax=max(vals.max(), vals.min() * 1.01))
    fig = _fig(2.6 * cols + 1.6, 2.2 * len(systems) + 1.2)
    axes = fig.subplots(len(systems), cols, squeeze=False)
    for ri, sysname in enumerate(systems):
        for ci in range(cols):
            ax = axes[ri][ci]
            if sysname == "none":
                if ci == 0:
                    m = _matrix(g[g["system"] == "none"], timeouts, retries, metric)
                    _heat(ax, m, [f"{t}" for t in timeouts], [str(r) for r in retries], SEQ_CMAP, norm,
                          lambda v: f"{v:.0f}", title="no breaker")
                else:
                    ax.axis("off")
                    continue
            else:
                thr = thresholds[ci] if thresholds else None
                sub = g[(g["system"] == sysname) & (g["threshold"] == thr)]
                m = _matrix(sub, timeouts, retries, metric)
                _heat(ax, m, [f"{t}" for t in timeouts], [str(r) for r in retries], SEQ_CMAP, norm,
                      lambda v: f"{v:.0f}", title=f"{sysname}, threshold {thr}")
            if ci == 0:
                ax.set_ylabel("retries", fontsize=8, color=INK2)
            if ri == len(systems) - 1:
                ax.set_xlabel("per-attempt timeout (ms)", fontsize=8, color=INK2)
    phase = g["phase"].iloc[0]
    fig.suptitle(f"{scenario}: {metric} in ms ({'fault window' if phase == 'fault' else 'whole run'}), mean over repeats",
                 x=0.01, ha="left", fontsize=10, color=INK)
    fig.get_layout_engine().set(w_pad=0.08, h_pad=0.1)
    sm = plt.cm.ScalarMappable(norm=norm, cmap=SEQ_CMAP)
    cbar = fig.colorbar(sm, ax=axes, shrink=0.8, pad=0.02)
    cbar.set_label(f"{metric} (ms, log scale)", fontsize=8, color=INK2)
    _plain_log_axis(cbar.ax.yaxis, norm.vmin, norm.vmax)
    cbar.ax.tick_params(labelsize=7, colors=INK2)
    return _save(fig, out)


def gain_map(gains: pd.DataFrame, scenario: str, out: Path, metric: str = "p99") -> Optional[Path]:
    """Relative gain over the no-breaker system at the same (timeout, retries): blue = lower latency, red = higher."""
    g = gains[gains["scenario"] == scenario]
    if g.empty:
        return None
    timeouts = sorted(g["timeout_ms"].unique())
    retries = sorted(g["retries"].unique())
    thresholds = sorted(int(x) for x in g["threshold"].dropna().unique())
    systems = [s for s in ("static", "adaptive") if s in set(g["system"])]
    col = f"gain_{metric}"
    lim = max(10.0, float(np.nanmax(np.abs(g[col]))))
    norm = TwoSlopeNorm(vmin=-lim, vcenter=0, vmax=lim)
    cols = max(1, len(thresholds))
    fig = _fig(2.6 * cols + 1.6, 2.2 * len(systems) + 1.2)
    axes = fig.subplots(len(systems), cols, squeeze=False)
    for ri, sysname in enumerate(systems):
        for ci in range(cols):
            ax = axes[ri][ci]
            thr = thresholds[ci] if thresholds else None
            sub = g[(g["system"] == sysname) & (g["threshold"] == thr)]
            m = _matrix(sub, timeouts, retries, col)
            _heat(ax, m, [str(t) for t in timeouts], [str(r) for r in retries], DIV_CMAP, norm,
                  lambda v: f"{v:+.0f}%", title=f"{sysname}, threshold {thr}")
            if ci == 0:
                ax.set_ylabel("retries", fontsize=8, color=INK2)
            if ri == len(systems) - 1:
                ax.set_xlabel("per-attempt timeout (ms)", fontsize=8, color=INK2)
    fig.suptitle(f"{scenario}: {metric} gain vs. no breaker at the same timeout/retries "
                 f"(positive = lower latency)", x=0.01, ha="left", fontsize=10, color=INK)
    sm = plt.cm.ScalarMappable(norm=norm, cmap=DIV_CMAP)
    cbar = fig.colorbar(sm, ax=axes, shrink=0.8, pad=0.02)
    cbar.set_label("gain (%)", fontsize=8, color=INK2)
    cbar.ax.tick_params(labelsize=7, colors=INK2)
    return _save(fig, out)


def gain_strip(gains: pd.DataFrame, out: Path, threshold: float = 15.0) -> Optional[Path]:
    """Per-cell gain for avg / p95 / p99: does the average-latency gain survive in the tail, and across configs?"""
    if gains.empty:
        return None
    scenarios = sorted(gains["scenario"].unique())
    fig = _fig(2.9 * len(scenarios) + 0.6, 3.4)
    axes = fig.subplots(1, len(scenarios), squeeze=False, sharey=True)[0]
    rng = np.random.default_rng(0)
    for ax, sc in zip(axes, scenarios):
        _style(ax)
        ax.grid(axis="x", visible=False)
        g = gains[gains["scenario"] == sc]
        for xi, m in enumerate(("avg", "p95", "p99")):
            for sysname, off in (("static", -0.17), ("adaptive", 0.17)):
                v = g[g["system"] == sysname][f"gain_{m}"].dropna().values
                if len(v):
                    ax.scatter(xi + off + rng.uniform(-0.07, 0.07, len(v)), v, s=14, color=SYS_COLOR[sysname],
                               marker=SYS_MARK[sysname], alpha=0.8, linewidths=0)
                    ax.hlines(np.median(v), xi + off - 0.12, xi + off + 0.12, color=INK, linewidth=1.4, zorder=5)
        ax.axhline(threshold, color=INK2, linewidth=0.8, linestyle=(0, (4, 3)))
        ax.axhline(0, color="#c3c2b7", linewidth=0.8)
        ax.set_xticks(range(3))
        ax.set_xticklabels(["avg", "p95", "p99"])
        ax.set_title(sc, fontsize=9, color=INK, loc="left")
        ax.text(2.45, threshold, f"{threshold:g}% (base paper)", fontsize=7, color=INK2, ha="right", va="bottom")
    axes[0].set_ylabel("gain vs. no breaker (%), one dot per grid cell", fontsize=8)
    handles = [Line2D([], [], marker=SYS_MARK[s], color=SYS_COLOR[s], linestyle="", markersize=6, label=SYS_LABEL[s])
               for s in ("static", "adaptive")] + [Line2D([], [], color=INK, linewidth=1.4, label="median over cells")]
    fig.legend(handles=handles, loc="lower center", ncol=3, frameon=False, fontsize=8, labelcolor=INK2,
               bbox_to_anchor=(0.5, -0.04))
    return _save(fig, out)


def cpu_figure(cpu: pd.DataFrame, out: Path) -> Optional[Path]:
    """CPU (percent of one core) per service, grouped by system, one panel per scenario, 95 % CI whiskers."""
    if cpu.empty:
        return None
    scenarios = sorted(cpu["scenario"].unique())
    ncol = min(3, len(scenarios))
    nrow = int(np.ceil(len(scenarios) / ncol))
    fig = _fig(3.6 * ncol, 2.7 * nrow + 0.5)
    axes = fig.subplots(nrow, ncol, squeeze=False)
    services = ["inventory", "payment", "order"]
    systems = [s for s in ("none", "static", "adaptive") if s in set(cpu["system"])]
    w = 0.8 / len(systems)
    for k, sc in enumerate(scenarios):
        ax = axes[k // ncol][k % ncol]
        _style(ax)
        ax.grid(axis="x", visible=False)
        g = cpu[cpu["scenario"] == sc]
        for si, sysname in enumerate(systems):
            for xi, svc in enumerate(services):
                r = g[(g["system"] == sysname) & (g["service"] == svc)]
                if r.empty or np.isnan(r["cpu_pct"].iloc[0]):
                    continue
                v = r["cpu_pct"].iloc[0]
                x = xi - 0.4 + w * (si + 0.5)
                ax.bar(x, v, width=w * 0.86, color=SYS_COLOR[sysname], zorder=3)
                lo, hi = r["cpu_pct_lo"].iloc[0], r["cpu_pct_hi"].iloc[0]
                if not np.isnan(lo):
                    ax.vlines(x, lo, hi, color=INK, linewidth=0.9, zorder=4)
        ax.set_xticks(range(len(services)))
        ax.set_xticklabels([f"{s}-service" for s in services], fontsize=7)
        ax.set_title(sc, fontsize=9, color=INK, loc="left")
        if k % ncol == 0:
            ax.set_ylabel("CPU (% of one core)", fontsize=8)
    for k in range(len(scenarios), nrow * ncol):
        axes[k // ncol][k % ncol].axis("off")
    handles = [plt.Rectangle((0, 0), 1, 1, color=SYS_COLOR[s], label=SYS_LABEL[s]) for s in systems]
    fig.legend(handles=handles, loc="lower center", ncol=len(systems), frameon=False, fontsize=8, labelcolor=INK2,
               bbox_to_anchor=(0.5, -0.03))
    return _save(fig, out)


def cpu_efficiency_figure(cpu: pd.DataFrame, out: Path) -> Optional[Path]:
    """CPU-seconds per 1000 *successful* requests of the order service (where the breaker runs)."""
    g0 = cpu[cpu["service"] == "order"]
    if g0.empty:
        return None
    scenarios = sorted(g0["scenario"].unique())
    systems = [s for s in ("none", "static", "adaptive") if s in set(g0["system"])]
    fig = _fig(1.3 * len(scenarios) * len(systems) / 2 + 3, 3.0)
    ax = fig.subplots()
    _style(ax)
    ax.grid(axis="x", visible=False)
    w = 0.8 / len(systems)
    for si, sysname in enumerate(systems):
        for xi, sc in enumerate(scenarios):
            r = g0[(g0["scenario"] == sc) & (g0["system"] == sysname)]
            if r.empty or np.isnan(r["cpu_s_per_1k_ok"].iloc[0]):
                continue
            x = xi - 0.4 + w * (si + 0.5)
            ax.bar(x, r["cpu_s_per_1k_ok"].iloc[0], width=w * 0.86, color=SYS_COLOR[sysname], zorder=3)
            lo, hi = r["cpu_s_per_1k_lo"].iloc[0], r["cpu_s_per_1k_hi"].iloc[0]
            if not np.isnan(lo):
                ax.vlines(x, lo, hi, color=INK, linewidth=0.9, zorder=4)
    ax.set_xticks(range(len(scenarios)))
    ax.set_xticklabels(scenarios, fontsize=8)
    ax.set_ylabel("order-service CPU-seconds per 1000 successful requests", fontsize=8)
    handles = [plt.Rectangle((0, 0), 1, 1, color=SYS_COLOR[s], label=SYS_LABEL[s]) for s in systems]
    ax.legend(handles=handles, frameon=False, fontsize=8, labelcolor=INK2, loc="upper left")
    return _save(fig, out)


def timeseries_figure(series: Dict[str, pd.DataFrame], thresholds: Dict[str, pd.DataFrame], fault_window: Optional[Tuple[float, float]],
                      title: str, out: Path) -> Optional[Path]:
    """Top: pooled p99 of end-to-end latency per time bucket (log scale) per system. Bottom: breaker threshold
    trajectory of the adaptive system (mean over repeats) versus the static threshold."""
    if not series:
        return None
    fig = _fig(7.2, 5.2)
    ax1, ax2 = fig.subplots(2, 1, sharex=True, gridspec_kw={"height_ratios": [3, 1.6]})
    for ax in (ax1, ax2):
        _style(ax)
        if fault_window:
            ax.axvspan(fault_window[0], fault_window[1], color="#e1e0d9", alpha=0.55, linewidth=0, zorder=0)
    for sysname, d in series.items():
        ax1.plot(d["t"], d["p99"], color=SYS_COLOR[sysname], marker=SYS_MARK[sysname], markersize=3.5, linewidth=1.4,
                 label=SYS_LABEL[sysname], zorder=3)
    ax1.set_yscale("log")
    lo, hi = ax1.get_ylim()
    _plain_log_axis(ax1.yaxis, lo, hi)
    ax1.set_ylim(lo, hi)
    ax1.set_ylabel("p99 latency (ms, log scale)", fontsize=8)
    fig.suptitle(title, x=0.01, ha="left", fontsize=9, color=INK)
    if fault_window:
        ax1.text(fault_window[0], ax1.get_ylim()[0] * 1.15, " fault window", fontsize=7, color=INK2, va="bottom")
    for sysname, d in thresholds.items():
        ax2.step(d["t"], d["threshold"], where="post", color=SYS_COLOR[sysname], linewidth=1.5, label=SYS_LABEL[sysname])
    ax2.set_ylabel("trip threshold\n(consecutive failures)", fontsize=8)
    ax2.set_xlabel("seconds since start of the measured period", fontsize=8)
    handles = [Line2D([], [], color=SYS_COLOR[s_], marker=SYS_MARK[s_], markersize=4, linewidth=1.4, label=SYS_LABEL[s_])
               for s_ in series]
    fig.legend(handles=handles, loc="outside lower center", ncol=len(handles), frameon=False, fontsize=8,
               labelcolor=INK2)
    return _save(fig, out)
