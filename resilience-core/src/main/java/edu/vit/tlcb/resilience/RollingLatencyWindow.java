package edu.vit.tlcb.resilience;

import org.HdrHistogram.Histogram;

/**
 * Latency distribution over the last {@code windowMs} milliseconds, kept as a ring of one-second
 * HdrHistogram buckets. Recording is O(1); a snapshot merges the live buckets, so percentiles are exact to
 * HdrHistogram precision (2 significant digits) rather than approximated by an exponential decay.
 *
 * <p>Time is passed in explicitly (nanoseconds from any monotonic origin) so that tests can replay
 * synthetic latency traces with a simulated clock.
 */
public final class RollingLatencyWindow {

    /** Percentiles of the window at one instant; latencies in milliseconds, NaN if the window is empty. */
    public record Snapshot(long count, double p50Ms, double p95Ms, double p99Ms, double meanMs) {
    }

    private static final long BUCKET_MS = 1000;
    private static final long MAX_MICROS = 600_000_000L; // 10 minutes

    private final int bucketCount;
    private final Histogram[] buckets;
    private final long[] bucketEpoch; // which absolute second each ring slot currently holds; -1 = unused
    private final Histogram scratch = new Histogram(MAX_MICROS, 2);

    public RollingLatencyWindow(long windowMs) {
        this.bucketCount = (int) Math.max(1, windowMs / BUCKET_MS);
        this.buckets = new Histogram[bucketCount];
        this.bucketEpoch = new long[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            buckets[i] = new Histogram(MAX_MICROS, 2);
            bucketEpoch[i] = -1;
        }
    }

    public synchronized void record(long nowNanos, long latencyNanos) {
        long second = nowNanos / 1_000_000L / BUCKET_MS;
        int slot = (int) Math.floorMod(second, (long) bucketCount);
        if (bucketEpoch[slot] != second) {
            buckets[slot].reset();
            bucketEpoch[slot] = second;
        }
        long micros = Math.max(1, Math.min(MAX_MICROS, latencyNanos / 1000));
        buckets[slot].recordValue(micros);
    }

    public synchronized Snapshot snapshot(long nowNanos) {
        long second = nowNanos / 1_000_000L / BUCKET_MS;
        scratch.reset();
        for (int i = 0; i < bucketCount; i++) {
            long e = bucketEpoch[i];
            if (e >= 0 && e > second - bucketCount && e <= second) {
                scratch.add(buckets[i]);
            }
        }
        long n = scratch.getTotalCount();
        if (n == 0) {
            return new Snapshot(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        return new Snapshot(n,
                scratch.getValueAtPercentile(50) / 1000.0,
                scratch.getValueAtPercentile(95) / 1000.0,
                scratch.getValueAtPercentile(99) / 1000.0,
                scratch.getMean() / 1000.0);
    }

    public synchronized void reset() {
        for (int i = 0; i < bucketCount; i++) {
            buckets[i].reset();
            bucketEpoch[i] = -1;
        }
    }
}
