# The tail-latency-adaptive circuit breaker

This document explains the design of the contribution: what is adapted, by which rule, how the threshold of a
running breaker can be changed at all, and why the rule is shaped the way it is. Code:
`resilience-core/src/main/java/edu/vit/tlcb/resilience/`.

## 1. What is adapted

The static baseline (an Istio-style outlier-detection analogue) opens after **N consecutive failures**. The
adaptive breaker keeps that rule but makes **N a live value**: it is *tightened* (smaller N, trips earlier) when
the tail latency of the dependency is climbing and *relaxed* back toward the configured baseline when the tail has
recovered. The sweep's `failureThreshold` is the baseline N of the adaptive breaker; its adaptation range is
`[minThreshold, maxThreshold]` (default `[2, baseline]`).

A "failure" is exactly what it is for the static breaker: an HTTP 5xx, a timeout, or an I/O error. Slow
responses that still complete within the time limit are not failures; they influence the breaker only through the
latency window that drives the adaptation. (With the study's grid every timeout is at most the 3 s injected delay,
so a delayed call becomes a timeout failure anyway.)

## 2. Signal: rolling tail latency and its trend

```
every call outcome (success, 5xx, timeout)  ->  RollingLatencyWindow (30 s, one HdrHistogram per second)
every evalInterval (5 s):  snapshot -> p95, p99
                           TailTrendTracker: EWMA(p99), OLS slope of the last N=6 p99 values (ms/s)
                           relative slope = slope / max(mean p99 over those N values, floor)
                           ThresholdStrategy.decide(trend, current, baseline, min, max) -> new threshold
```

* Fast-fail **rejections by an open breaker are not recorded**: they carry no information about the dependency
  and would make an open breaker look like a healed one.
* The slope is **relative** (per second, as a fraction of the recent p99 level) rather than in ms/s. With a
  fixed ms/s threshold the adaptation would be blind at a 500 ms timeout (p99 cannot exceed the timeout) and
  hypersensitive at a 3 s timeout, silently confounding the timeout dimension of the sweep. A step from a
  healthy p99 to a degraded one gives a relative slope of about 0.14 /s *regardless of the absolute levels*
  (unit-tested in `WindowAndTrendTest.relativeSlopeIsScaleFreeAcrossTimeoutSettings`).
* Windows with fewer than `minSamples` (20) observations are skipped (`NO_DATA`).

## 3. Rule (`SlopeHysteresisStrategy`, interface `ThresholdStrategy`)

| Situation | Action |
|---|---|
| relative slope >= `riseSlope` (0.08 /s) | **TIGHTEN**: `N <- max(min, floor(N * 0.5))` (multiplicative decrease, fast) |
| relative slope in the dead band (`relaxSlope` 0.02 .. `riseSlope` 0.08) | HOLD, and the calm counter restarts |
| slope <= `relaxSlope` **and** p99-EWMA <= `relaxLevelRatio` (0.5) x peak p99 since tightening, for `relaxAfterCalmTicks` (3) consecutive evaluations | **RELAX**: `N <- min(baseline, N + 1)` (additive increase, slow) |
| otherwise | HOLD |

Properties:

* **Hysteresis** comes from three things: separate rise/relax thresholds (dead band), the calm counter that must
  complete *uninterrupted*, and the asymmetric step sizes. The tests replay two fault episodes and assert that
  the direction reverses only around genuine changes.
* **Clamp**: the result is clamped to `[min, max]` by the controller regardless of what a strategy returns.
* **Stable-but-bad does not relax.** The literal reading of "relax if tail latency is stable or recovering"
  would relax during a *plateau* of high latency (slope 0). That is the worst moment to loosen a breaker, so
  relaxation additionally requires the p99 to have fallen to at most half of its peak since tightening.
  This is a deliberate refinement of the specification; `relaxLevelRatio = 1.0` restores the literal rule.
* Adaptation is **audited**: every change is logged at INFO (`ADAPT breaker=... TIGHTEN threshold 10 -> 5 |
  reason | p95 p99 ewma slope`) and kept as an `AdaptationEvent`; every evaluation (change or not) is kept as a
  trace point. Both are exported in `run_k.json` (`services.order-service.resilience.targets.*`).
* `FixedThresholdStrategy` (`adaptive.strategy = "fixed"`) disables the adaptation and is the ablation that
  isolates the effect of the rule from the custom state machine.

## 4. How a *live* threshold is possible: custom state machine vs. replacing the Resilience4j breaker

Resilience4j configures window size and thresholds at construction; they cannot be changed on a running
`CircuitBreaker`. Two approaches were considered.

**A. Replace the breaker in the `CircuitBreakerRegistry` whenever the threshold changes.**
Rejected. The new breaker starts with an empty window, so the current failure streak is lost and adapting would
*delay* a trip that was about to happen; the OPEN/HALF_OPEN timers and probe permits would have to be migrated by
hand; calls in flight on the old instance report their outcome to an orphan; and every adaptation step churns
objects on the hot path. It also makes "the threshold at time t" ambiguous during the swap.

**B. Own the (tiny) state machine.** Chosen (`ConsecutiveFailureBreaker`). The whole machine is one counter and
three states; the threshold is a volatile read (`IntSupplier`) consulted on every failure, so it changes
mid-streak with no state loss; transitions are timestamped and delivered to listeners.

**Trade-off.** The adaptive system does not get Resilience4j's event bus and metrics integrations for free
(equivalent hooks are provided), and the machine's semantics must be *shown* to match the baseline instead of
being true by construction. That is what `Resilience4jBreakerEquivalenceTest` does: random traces of calls and
"open wait elapsed" events are fed to a Resilience4j breaker (window N / 100 % failure rate) and to the custom
machine with the same constant N, probes P in 1..3 with N >= P (over 0.6 million steps), asserting the same
permission decision and the same state after every step. Any difference measured between "static" and "adaptive"
is therefore due to the adaptation, not the implementation.

One Resilience4j quirk was found by this test and is pinned by its own test: Resilience4j evaluates the HALF_OPEN
outcome after `min(minimumNumberOfCalls, probes)` calls, i.e. after fewer probes than configured when N < P.
It cannot occur in the study grid (N in {3, 5, 10}, 3 probes); the custom machine always waits for all probes.

## 5. Parameters (all in `ResilienceSettings.AdaptiveSettings`, constant across the sweep)

| Parameter | Default | Meaning |
|---|---|---|
| `windowMs` | 30000 | rolling window length |
| `evalIntervalMs` | 5000 | evaluation period |
| `trendPoints` | 6 | window snapshots used for the slope (about 30 s of history) |
| `ewmaAlpha` | 0.3 | EWMA smoothing of the p99 |
| `riseSlopeRelPerSec` | 0.08 | tighten at or above this relative slope |
| `relaxSlopeRelPerSec` | 0.02 | relax only at or below this relative slope |
| `slopeFloorMs` | 50 | floor of the level used to normalise the slope |
| `tightenFactor` | 0.5 | multiplicative decrease |
| `relaxStep` | 1 | additive increase |
| `relaxAfterCalmTicks` | 3 | consecutive calm evaluations per relax step |
| `relaxLevelRatio` | 0.5 | p99-EWMA must be at most this fraction of the peak |
| `minThreshold` / `maxThreshold` | 2 / 0 (= baseline) | clamp |
| `minSamples` | 20 | evaluations with less data are skipped |

These defaults were chosen from the synthetic traces in the unit tests and a single live check, **not tuned on
experimental results**; tuning them against the sweep outcome would be circular. They can be overridden per run
(`--adaptive-json`) for a sensitivity study of the adaptation itself.

## 6. Known limitations of the rule

* The threshold only matters in the CLOSED state; it never changes the HALF_OPEN rule.
* A tightened threshold applies at the **next failure**, not retroactively to the current streak.
* With baseline 3 the adaptation range is only `[2, 3]`; the adaptive breaker can only differ materially from the
  static one for larger baselines.
* The adaptation reacts to *latency* trends. A purely error-based degradation (fast HTTP 500s) does not raise the
  p99 and therefore does not tighten the breaker; in that scenario adaptive and static are expected to coincide.
* After a restart of the order service the controller starts from its baseline with an empty window.
