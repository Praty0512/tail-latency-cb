package edu.vit.tlcb.resilience;

/** Receives state transitions. Called synchronously; implementations must be fast and non-blocking. */
@FunctionalInterface
public interface BreakerListener {
    void onStateTransition(StateTransition transition);
}
