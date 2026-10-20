package edu.vit.tlcb.common.fault;

/**
 * Stateless, counter-based pseudo random numbers (SplitMix64 finaliser).
 *
 * <p>A shared {@code java.util.Random} would give a reproducible <em>sequence</em> of draws, but which
 * request receives which draw would depend on thread scheduling, so two runs with the same seed could
 * still inject faults into different requests. Here every decision is a pure function of
 * {@code (seed, requestKey, attempt, ruleIndex, stream)}: the load generator assigns each request a
 * seeded key, so the <em>set of requests that are faulted</em> is identical across runs regardless of
 * arrival order. Retries carry an incremented attempt number, so they receive independent draws.
 */
public final class DeterministicRandom {

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private DeterministicRandom() {
    }

    /** SplitMix64 mixing function. */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 64-bit FNV-1a hash of a string, used to turn request keys into numbers. */
    public static long hash(String s) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001B3L;
        }
        return h;
    }

    /** Uniform double in [0,1) determined solely by the arguments. */
    public static double uniform(long seed, long key, int attempt, int ruleIndex, int stream) {
        long z = mix(seed + GOLDEN);
        z = mix(z ^ key);
        z = mix(z + GOLDEN * (attempt + 1L));
        z = mix(z ^ (GOLDEN * (ruleIndex + 1L)));
        z = mix(z + GOLDEN * (stream + 1L));
        return (z >>> 11) * 0x1.0p-53;
    }
}
