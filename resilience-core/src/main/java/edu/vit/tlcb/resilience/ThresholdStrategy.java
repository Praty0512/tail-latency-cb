package edu.vit.tlcb.resilience;

/**
 * Strategy that decides how the breaker's trip threshold (consecutive failures to open) should move, given
 * the current tail-latency trend. Implementations are small state machines (hysteresis needs memory) but
 * have no dependency on time, threads or the breaker, so they can be tested by feeding synthetic
 * {@link TailTrend} sequences.
 */
public interface ThresholdStrategy {

    enum Action { TIGHTEN, RELAX, HOLD }

    /** Everything a strategy may look at for one decision. */
    record Input(TailTrend trend, int currentThreshold, int baselineThreshold, int minThreshold, int maxThreshold,
                 long windowSamples) {
    }

    /** The decision; {@code threshold} may equal the current value (HOLD) and is clamped by the caller. */
    record Decision(int threshold, Action action, String reason) {
    }

    Decision decide(Input input);

    /** Called when an evaluation was skipped for lack of data; strategies should forget "calm" progress. */
    default void onNoData() {
    }

    /** Forget all internal state (new run / reconfiguration). */
    void reset();
}
