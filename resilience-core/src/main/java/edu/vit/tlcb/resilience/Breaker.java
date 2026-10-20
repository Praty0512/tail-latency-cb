package edu.vit.tlcb.resilience;

/**
 * Minimal circuit-breaker contract shared by the static (Resilience4j) and the tail-latency-adaptive
 * implementations, so the retry/time-limit composition in {@link ResilientCall} is identical for both and
 * the systems under comparison differ only in the breaker itself.
 *
 * <p>Usage protocol: call {@link #tryAcquirePermission()}; if it returns true, report exactly one of
 * {@link #onSuccess}, {@link #onFailure} or {@link #releasePermission()} for that call.
 */
public interface Breaker {

    String name();

    /** Asks for permission to start a call. False means the call must be rejected (fail fast). */
    boolean tryAcquirePermission();

    /** The permitted call finished successfully after {@code durationNanos}. */
    void onSuccess(long durationNanos);

    /** The permitted call failed (HTTP 5xx, timeout, I/O error) after {@code durationNanos}. */
    void onFailure(long durationNanos, Throwable error);

    /** The permitted call was abandoned without a verdict (e.g. cancelled); frees the permission. */
    void releasePermission();

    BreakerState state();

    /** Number of consecutive failures that currently trips the breaker; -1 if not applicable. */
    int failureThreshold();

    /** Returns the breaker to CLOSED with empty history. */
    void reset();

    void addListener(BreakerListener listener);

    /** Releases background resources (schedulers); the breaker must not be used afterwards. */
    default void close() {
    }
}
