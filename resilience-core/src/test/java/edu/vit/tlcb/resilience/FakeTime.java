package edu.vit.tlcb.resilience;

import java.util.concurrent.atomic.AtomicLong;

/** Simulated clock for deterministic tests: one object provides both the monotonic and the wall clock. */
final class FakeTime {

    private final AtomicLong nanos = new AtomicLong(1_000L * 1_000_000_000L);
    private final long epochBaseMs = 1_700_000_000_000L;
    private final long nanosBase = nanos.get();

    long nanos() {
        return nanos.get();
    }

    long epochMs() {
        return epochBaseMs + (nanos.get() - nanosBase) / 1_000_000L;
    }

    void advanceMs(long ms) {
        nanos.addAndGet(ms * 1_000_000L);
    }

    void advanceSec(long s) {
        advanceMs(s * 1000);
    }
}
