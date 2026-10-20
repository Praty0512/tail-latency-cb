package edu.vit.tlcb.resilience;

/** A circuit-breaker state change with its wall-clock time and a human-readable cause. */
public record StateTransition(long epochMs, String breaker, BreakerState from, BreakerState to, String reason) {
}
