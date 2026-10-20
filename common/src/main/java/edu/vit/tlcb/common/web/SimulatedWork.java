package edu.vit.tlcb.common.web;

/**
 * Deterministic CPU work that stands in for business logic, so that per-service CPU measurements are
 * non-trivial. Work is expressed as loop iterations (not wall-clock time) so the CPU cost scales
 * correctly under contention.
 */
public final class SimulatedWork {

    private static volatile long sink;

    private SimulatedWork() {
    }

    /** Burns CPU with a xorshift loop; roughly 1 ns per iteration on a modern core. */
    public static void burn(long iterations) {
        long x = 0x2545F4914F6CDD1DL ^ System.nanoTime();
        for (long i = 0; i < iterations; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
        }
        sink = x; // defeat dead-code elimination
    }
}
