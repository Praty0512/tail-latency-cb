package edu.vit.tlcb.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * The <b>static baseline</b>: a stock Resilience4j {@link CircuitBreaker} configured to mimic Istio's
 * outlier-detection rule "eject after N consecutive 5xx errors, re-admit after a fixed wait".
 *
 * <h3>Why no custom consecutive-error counter is needed</h3>
 * Resilience4j opens when the failure <em>rate</em> over a sliding window reaches a threshold. With a
 * COUNT_BASED window of size N, {@code minimumNumberOfCalls = N} and {@code failureRateThreshold = 100},
 * the breaker opens exactly when the last N recorded calls all failed, which is the definition of "N
 * consecutive failures". {@code Resilience4jBreakerEquivalenceTest} verifies this against an independent
 * consecutive-failure state machine on random traces.
 *
 * <h3>Where Resilience4j and Istio still differ (see README, "Limitations")</h3>
 * <ul>
 *   <li><b>Scope.</b> Istio ejects an individual <em>endpoint</em> from a load-balancing pool and traffic
 *       shifts to the remaining replicas; here there is one instance per dependency, so the breaker
 *       protects the <em>dependency</em> and rejected calls fail fast (HTTP 503), the closest analogue of
 *       Envoy answering "no healthy upstream".</li>
 *   <li><b>Re-admission.</b> Envoy returns an ejected host to the pool completely after
 *       {@code baseEjectionTime x (number of ejections)} and needs N <em>new</em> consecutive errors to eject
 *       it again; Resilience4j goes through HALF_OPEN, admits a limited number of probe calls and decides
 *       on their outcome. The ejection time does not grow with repeated ejections here.</li>
 *   <li><b>Panic / ejection limits.</b> Envoy caps the share of ejected hosts ({@code maxEjectionPercent})
 *       and ignores health status below a "panic threshold"; there is no equivalent here.</li>
 *   <li><b>Half-open decision rule.</b> One {@code failureRateThreshold} serves both states in Resilience4j, so
 *       with 100 % the breaker re-opens only if <em>all</em> probe calls fail. Resilience4j also evaluates
 *       the probes after {@code min(minimumNumberOfCalls, probes)} calls, i.e. after fewer than the
 *       configured number when the threshold N is below the probe count; with the study's grid (N in
 *       {3,5,10}, 3 probes) this never happens.</li>
 * </ul>
 */
public final class Resilience4jBreaker implements Breaker {

    private final String name;
    private final int threshold;
    private final CircuitBreaker delegate;
    private final List<BreakerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * @param threshold          consecutive failures that open the breaker (the window size)
     * @param openWait           time spent OPEN before probing
     * @param halfOpenProbes     number of probe calls admitted in HALF_OPEN
     * @param automaticHalfOpen  if true a timer moves OPEN to HALF_OPEN exactly when the wait elapses
     *                           (accurate timeline); if false the move happens lazily on the next call
     */
    public Resilience4jBreaker(String name, int threshold, Duration openWait, int halfOpenProbes,
                               boolean automaticHalfOpen) {
        this.name = name;
        this.threshold = threshold;
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(threshold)
                .minimumNumberOfCalls(threshold)
                .failureRateThreshold(100f)
                .waitDurationInOpenState(openWait)
                .permittedNumberOfCallsInHalfOpenState(halfOpenProbes)
                .automaticTransitionFromOpenToHalfOpenEnabled(automaticHalfOpen)
                .recordException(t -> true) // classification is done by the caller; every reported failure counts
                .build();
        this.delegate = CircuitBreaker.of(name, config);
        delegate.getEventPublisher().onStateTransition(e -> {
            BreakerState from = map(e.getStateTransition().getFromState());
            BreakerState to = map(e.getStateTransition().getToState());
            StateTransition t = new StateTransition(System.currentTimeMillis(), name, from, to, reason(from, to));
            for (BreakerListener l : listeners) l.onStateTransition(t);
        });
    }

    private String reason(BreakerState from, BreakerState to) {
        if (from == BreakerState.CLOSED && to == BreakerState.OPEN) {
            return threshold + " consecutive failures (static threshold " + threshold + ")";
        }
        if (from == BreakerState.OPEN && to == BreakerState.HALF_OPEN) return "open wait elapsed";
        if (from == BreakerState.HALF_OPEN && to == BreakerState.CLOSED) return "probe calls succeeded";
        if (from == BreakerState.HALF_OPEN && to == BreakerState.OPEN) return "all probe calls failed";
        return "transition";
    }

    private static BreakerState map(CircuitBreaker.State s) {
        return switch (s) {
            case CLOSED -> BreakerState.CLOSED;
            case OPEN -> BreakerState.OPEN;
            case HALF_OPEN -> BreakerState.HALF_OPEN;
            default -> BreakerState.NONE;
        };
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean tryAcquirePermission() {
        return delegate.tryAcquirePermission();
    }

    @Override
    public void onSuccess(long durationNanos) {
        delegate.onSuccess(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void onFailure(long durationNanos, Throwable error) {
        delegate.onError(durationNanos, TimeUnit.NANOSECONDS, error);
    }

    @Override
    public void releasePermission() {
        delegate.releasePermission();
    }

    @Override
    public BreakerState state() {
        return map(delegate.getState());
    }

    @Override
    public int failureThreshold() {
        return threshold;
    }

    @Override
    public void reset() {
        delegate.reset();
    }

    @Override
    public void addListener(BreakerListener listener) {
        listeners.add(listener);
    }

    /** Underlying Resilience4j breaker (for tests and diagnostics). */
    CircuitBreaker unwrap() {
        return delegate;
    }
}
