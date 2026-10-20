package edu.vit.tlcb.resilience;

/** Observes the individual HTTP attempts made by a {@link ResilientCall} (retries are separate attempts). */
@FunctionalInterface
public interface ExecutionObserver {

    /**
     * @param durationNanos wall time of the attempt (a timed-out attempt reports the time limit)
     * @param failure       true if the attempt failed (5xx, timeout, I/O error)
     */
    void onAttempt(long durationNanos, boolean failure);

    ExecutionObserver NOOP = (d, f) -> {
    };
}
