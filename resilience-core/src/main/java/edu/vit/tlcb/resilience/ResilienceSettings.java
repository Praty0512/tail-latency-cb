package edu.vit.tlcb.resilience;

/**
 * Complete, runtime-switchable resilience configuration of the order service ({@code POST /admin/config}).
 * Plain mutable fields with defaults so that a partial JSON document only overrides what it names.
 *
 * <p>The three sweep dimensions of the study are {@link #timeoutMs}, {@link #retries} and
 * {@link #failureThreshold}; everything else is held fixed across the sweep.
 */
public class ResilienceSettings {

    /** Which system is under test. */
    public enum SystemType {
        /** Timeout and retry only; no circuit breaker. */
        NONE,
        /** Resilience4j consecutive-failure breaker with a fixed threshold (Istio-like baseline). */
        STATIC,
        /** Tail-latency-adaptive breaker; {@link #failureThreshold} is its baseline. */
        ADAPTIVE
    }

    /** Which failures are retried. */
    public enum RetryPolicy {
        /** Every failure: HTTP 5xx, timeout, connection error. */
        ALL_FAILURES,
        /** Istio's default-style {@code retryOn: gateway-error,connect-failure}: 502/503/504, timeouts, connect errors (not 500). */
        ISTIO_GATEWAY_ERROR
    }

    /** Identifier carried into every metrics row; the harness derives it from scenario + system + grid cell. */
    public String configId = "default";
    public SystemType system = SystemType.STATIC;

    /** Per-attempt timeout enforced by the Resilience4j TimeLimiter (sweep dimension 1). */
    public long timeoutMs = 2000;
    /** Retries after the first attempt (sweep dimension 2). Total attempts = retries + 1. */
    public int retries = 0;
    /** Pause between attempts (Envoy's default base retry interval is 25 ms). */
    public long retryWaitMs = 25;
    public RetryPolicy retryOn = RetryPolicy.ALL_FAILURES;

    /** Consecutive failures that open the breaker (sweep dimension 3; baseline for the adaptive breaker). */
    public int failureThreshold = 5;
    /** Time spent OPEN before probing. 10 s per the base paper's text (its Fig 5 YAML says baseEjectionTime 30s). */
    public long openWaitMs = 10_000;
    public int halfOpenProbes = 3;

    public AdaptiveSettings adaptive = new AdaptiveSettings();

    /** Tuning of the tail-latency adaptation; held constant across the sweep. */
    public static class AdaptiveSettings {
        /** {@code slope-hysteresis} (the contribution) or {@code fixed} (adaptation disabled; ablation). */
        public String strategy = "slope-hysteresis";
        /** Length of the rolling latency window. */
        public long windowMs = 30_000;
        /** How often the controller re-evaluates the window. */
        public long evalIntervalMs = 5_000;
        /** Number of most recent window snapshots used for the slope. */
        public int trendPoints = 6;
        public double ewmaAlpha = 0.3;
        /** Tighten when the p99 slope, relative to the recent p99 level, is at least this much per second. */
        public double riseSlopeRelPerSec = 0.08;
        /** Relaxing is only considered when the relative slope is at most this (dead band between the two). */
        public double relaxSlopeRelPerSec = 0.02;
        /** Floor for the p99 level used to normalise the slope, so near-zero latencies do not inflate it. */
        public double slopeFloorMs = 50;
        /** On tighten: new threshold = floor(current * factor), at least one step down, clamped to min. */
        public double tightenFactor = 0.5;
        public int relaxStep = 1;
        /** Consecutive calm evaluations required before each relax step. */
        public int relaxAfterCalmTicks = 3;
        /** p99 EWMA must have fallen to at most this fraction of its peak since tightening before relaxing. */
        public double relaxLevelRatio = 0.5;
        public int minThreshold = 2;
        /** Upper clamp; 0 means "the baseline threshold". */
        public int maxThreshold = 0;
        /** Evaluations with fewer samples than this in the window are skipped. */
        public int minSamples = 20;
    }

    /** Returns an error message, or null if the settings are valid. */
    public String validate() {
        if (system == null) return "system is required";
        if (timeoutMs < 1) return "timeoutMs must be >= 1";
        if (retries < 0 || retries > 10) return "retries must be within [0,10]";
        if (retryWaitMs < 0) return "retryWaitMs must be >= 0";
        if (failureThreshold < 1) return "failureThreshold must be >= 1";
        if (openWaitMs < 1) return "openWaitMs must be >= 1";
        if (halfOpenProbes < 1) return "halfOpenProbes must be >= 1";
        AdaptiveSettings a = adaptive == null ? new AdaptiveSettings() : adaptive;
        if (a.windowMs < 1000 || a.evalIntervalMs < 100) return "adaptive window/eval interval too small";
        if (a.trendPoints < 3) return "adaptive.trendPoints must be >= 3";
        if (a.ewmaAlpha <= 0 || a.ewmaAlpha > 1) return "adaptive.ewmaAlpha must be within (0,1]";
        if (a.tightenFactor <= 0 || a.tightenFactor >= 1) return "adaptive.tightenFactor must be within (0,1)";
        if (a.relaxSlopeRelPerSec > a.riseSlopeRelPerSec) return "relax slope must not exceed rise slope (no hysteresis band)";
        if (a.minThreshold < 1 || a.minThreshold > failureThreshold) return "adaptive.minThreshold must be within [1, failureThreshold]";
        if (a.maxThreshold != 0 && a.maxThreshold < failureThreshold) return "adaptive.maxThreshold must be 0 or >= failureThreshold";
        return null;
    }
}
