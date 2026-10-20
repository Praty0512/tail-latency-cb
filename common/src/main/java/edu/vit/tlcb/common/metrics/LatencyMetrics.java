package edu.vit.tlcb.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry of all latency series of a service. The three <em>directions</em> are:
 * <ul>
 *   <li>{@code inbound}: every business endpoint of this service, as seen by its callers;</li>
 *   <li>{@code outbound}: every logical call to a downstream service, as the application experiences it
 *       (including retries, waits and fast-fail rejections by an open breaker);</li>
 *   <li>{@code attempt}: each individual HTTP attempt of an outbound call (excludes rejections).</li>
 * </ul>
 */
public class LatencyMetrics {

    public static final String INBOUND = "inbound";
    public static final String OUTBOUND = "outbound";
    public static final String ATTEMPT = "attempt";

    private final MeterRegistry registry;
    private final ConcurrentMap<String, LatencySeries> series = new ConcurrentHashMap<>();

    public LatencyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public LatencySeries series(String direction, String endpoint) {
        String key = direction + "|" + endpoint;
        LatencySeries s = series.get(key);
        if (s == null) {
            s = series.computeIfAbsent(key, k -> new LatencySeries(direction, endpoint, meterName(direction), registry));
        }
        return s;
    }

    private static String meterName(String direction) {
        return switch (direction) {
            case INBOUND -> "tlcb.inbound.latency";
            case OUTBOUND -> "tlcb.outbound.latency";
            default -> "tlcb.outbound.attempt.latency";
        };
    }

    public void inbound(String endpoint, long nanos, boolean error) {
        series(INBOUND, endpoint).record(nanos, error, false);
    }

    public void outbound(String target, long nanos, boolean error, boolean rejected) {
        series(OUTBOUND, target).record(nanos, error, rejected);
    }

    public void attempt(String target, long nanos, boolean error) {
        series(ATTEMPT, target).record(nanos, error, false);
    }

    /** Makes a series exist before its first observation so it appears in every export tick. */
    public void declare(String direction, String endpoint) {
        series(direction, endpoint);
    }

    public List<LatencySeries> all() {
        return new ArrayList<>(series.values());
    }

    /**
     * Clears the per-run histograms so a new run starts empty. The series objects are kept (their gauges are
     * bound to them, and they keep appearing in every export tick with count 0); the Micrometer timers keep
     * their process-lifetime totals.
     */
    public void resetRunState() {
        for (LatencySeries s : series.values()) {
            s.resetRun();
        }
    }
}
