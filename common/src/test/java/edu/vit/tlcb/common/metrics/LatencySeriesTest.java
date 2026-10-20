package edu.vit.tlcb.common.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LatencySeriesTest {

    private static LatencySeries newSeries() {
        return new LatencySeries("inbound", "GET /api/x", "tlcb.inbound.latency", new SimpleMeterRegistry());
    }

    @Test
    void percentilesOfKnownDistributionAreAccurate() {
        LatencySeries s = newSeries();
        for (int ms = 1; ms <= 100; ms++) s.record(TimeUnit.MILLISECONDS.toNanos(ms), false, false);
        LatencySeries.Stats st = s.drain();
        assertThat(st.count()).isEqualTo(100);
        assertThat(st.p50()).isCloseTo(50.0, within(0.2));
        assertThat(st.p95()).isCloseTo(95.0, within(0.2));
        assertThat(st.p99()).isCloseTo(99.0, within(0.2));
        assertThat(st.avg()).isCloseTo(50.5, within(0.2));
    }

    @Test
    void intervalsAreIndependentAndCumulativeAccumulates() {
        LatencySeries s = newSeries();
        for (int i = 0; i < 90; i++) s.record(TimeUnit.MILLISECONDS.toNanos(10), false, false);
        LatencySeries.Stats first = s.drain();
        for (int i = 0; i < 10; i++) s.record(TimeUnit.MILLISECONDS.toNanos(1000), true, false);
        LatencySeries.Stats second = s.drain();
        LatencySeries.Stats empty = s.drain();

        assertThat(first.p99()).isCloseTo(10.0, within(0.1));
        assertThat(second.p50()).isCloseTo(1000.0, within(1.0)); // not contaminated by the first interval
        assertThat(second.errorRate()).isEqualTo(1.0);
        assertThat(empty.count()).isZero();
        assertThat(empty.p99()).isNaN();

        LatencySeries.Stats total = s.cumulativeStats();
        assertThat(total.count()).isEqualTo(100);
        assertThat(total.p99()).isCloseTo(1000.0, within(1.0)); // 10 % of calls are slow => p99 is slow
        assertThat(total.errorRate()).isCloseTo(0.10, within(1e-9));
    }

    @Test
    void rejectedCallsAreCountedSeparately() {
        LatencySeries s = newSeries();
        s.record(TimeUnit.MICROSECONDS.toNanos(50), true, true);
        s.record(TimeUnit.MILLISECONDS.toNanos(5), false, false);
        LatencySeries.Stats st = s.drain();
        assertThat(st.rejected()).isEqualTo(1);
        assertThat(st.errors()).isEqualTo(1);
    }

    @Test
    void csvSchemaStartsWithTheRequiredColumns() {
        String[] cols = MetricRow.CSV_HEADER.split(",");
        assertThat(cols).startsWith("timestamp", "service", "p50", "p95", "p99", "avg",
                "error_rate", "cpu_pct", "cb_state", "config_id");
        MetricRow row = new MetricRow("2026-01-01T00:00:00Z", "order-service", 1.0, 2.0, 3.0, 1.5, 0.25, 12.5,
                "OPEN", "cfg", "outbound", "payment-service", 10, 2, 1, 5.0, 3);
        assertThat(row.toCsvLine().split(",", -1)).hasSize(cols.length);
        MetricRow empty = new MetricRow("t", "s", null, null, null, null, null, null, "NA", "c",
                "inbound", "e", 0, 0, 0, 0, -1);
        assertThat(empty.toCsvLine().split(",", -1)).hasSize(cols.length);
    }
}
