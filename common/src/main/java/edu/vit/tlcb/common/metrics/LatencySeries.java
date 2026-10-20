package edu.vit.tlcb.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One latency time series, e.g. "inbound POST /api/orders" or "outbound payment-service".
 *
 * <p>Every observation goes to two places:
 * <ul>
 *   <li>a Micrometer {@link Timer} with percentile histograms, scraped at {@code /actuator/prometheus};</li>
 *   <li>an HdrHistogram {@link Recorder}, which hands out a fresh <em>interval</em> histogram at every
 *       export tick. Micrometer's own percentiles are over a decaying window and cannot be cut into exact
 *       per-interval rows, so the CSV/JSON export uses the recorder. Percentiles are never averaged
 *       across intervals; run-level percentiles come from the cumulative histogram.</li>
 * </ul>
 */
public final class LatencySeries {

    /** Statistics of one interval (or of a whole run); latencies in milliseconds, NaN if empty. */
    public record Stats(long count, long errors, long rejected,
                        double p50, double p95, double p99, double p999, double avg, double max) {
        public double errorRate() {
            return count == 0 ? Double.NaN : Math.min(1.0, errors / (double) count);
        }
    }

    private final String direction;
    private final String endpoint;
    private final Recorder recorder = new Recorder(3);
    private final Histogram cumulative = new Histogram(3);
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private long cumulativeErrors;
    private long cumulativeRejected;
    private final Timer okTimer;
    private final Timer errorTimer;
    /** Last completed interval's p50/p95/p99 in seconds, exposed as plain gauges (see constructor). */
    private final double[] lastQuantilesSeconds = {Double.NaN, Double.NaN, Double.NaN};

    LatencySeries(String direction, String endpoint, String meterName, MeterRegistry registry) {
        this.direction = direction;
        this.endpoint = endpoint;
        this.okTimer = timer(meterName, endpoint, "ok", registry);
        this.errorTimer = timer(meterName, endpoint, "error", registry);
        // Since Micrometer 1.13 a timer with a percentile histogram is exposed to Prometheus as a native
        // histogram, which cannot carry quantile labels. Publish p50/p95/p99 of the last export interval
        // explicitly so they are readable at /actuator/prometheus without histogram_quantile().
        String[] q = {"0.5", "0.95", "0.99"};
        for (int i = 0; i < q.length; i++) {
            final int idx = i;
            Gauge.builder("tlcb.latency.quantile.seconds", this, s -> s.lastQuantilesSeconds[idx])
                    .description("Latency quantile over the most recent export interval")
                    .tag("direction", direction)
                    .tag("endpoint", endpoint)
                    .tag("quantile", q[i])
                    .register(registry);
        }
    }

    private static Timer timer(String name, String endpoint, String outcome, MeterRegistry registry) {
        return Timer.builder(name)
                .tag("endpoint", endpoint)
                .tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(60))
                .register(registry);
    }

    public String direction() {
        return direction;
    }

    public String endpoint() {
        return endpoint;
    }

    /** Records one observation. {@code wasRejected} marks calls refused by an open breaker (no downstream work). */
    public void record(long nanos, boolean error, boolean wasRejected) {
        long micros = Math.max(1, TimeUnit.NANOSECONDS.toMicros(nanos));
        recorder.recordValue(micros);
        if (error) errors.incrementAndGet();
        if (wasRejected) rejected.incrementAndGet();
        (error ? errorTimer : okTimer).record(nanos, TimeUnit.NANOSECONDS);
    }

    /** Returns the statistics accumulated since the previous drain and folds them into the cumulative total. */
    public synchronized Stats drain() {
        Histogram h = recorder.getIntervalHistogram();
        cumulative.add(h);
        long e = errors.getAndSet(0);
        long r = rejected.getAndSet(0);
        cumulativeErrors += e;
        cumulativeRejected += r;
        Stats st = stats(h, e, r);
        lastQuantilesSeconds[0] = st.p50() / 1000.0;
        lastQuantilesSeconds[1] = st.p95() / 1000.0;
        lastQuantilesSeconds[2] = st.p99() / 1000.0;
        return st;
    }

    /** Forgets everything recorded so far; the series object (and its registered meters) stay alive. */
    public synchronized void resetRun() {
        recorder.reset();
        cumulative.reset();
        errors.set(0);
        rejected.set(0);
        cumulativeErrors = 0;
        cumulativeRejected = 0;
        java.util.Arrays.fill(lastQuantilesSeconds, Double.NaN);
    }

    /** Statistics over everything drained so far (call {@link #drain()} first to include the open interval). */
    public synchronized Stats cumulativeStats() {
        return stats(cumulative, cumulativeErrors, cumulativeRejected);
    }

    private static Stats stats(Histogram h, long errors, long rejected) {
        long n = h.getTotalCount();
        if (n == 0) {
            return new Stats(0, errors, rejected, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        return new Stats(n, errors, rejected,
                h.getValueAtPercentile(50) / 1000.0,
                h.getValueAtPercentile(95) / 1000.0,
                h.getValueAtPercentile(99) / 1000.0,
                h.getValueAtPercentile(99.9) / 1000.0,
                h.getMean() / 1000.0,
                h.getMaxValue() / 1000.0);
    }
}
