package edu.vit.tlcb.resilience;

/** Circuit-breaker states. {@code NONE} is reported by the pass-through breaker used for the "no breaker" system. */
public enum BreakerState {
    CLOSED, OPEN, HALF_OPEN, NONE
}
