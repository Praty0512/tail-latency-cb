package edu.vit.tlcb.resilience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WindowAndTrendTest {

    private static final long MS = 1_000_000L;

    @Test
    void windowPercentilesAreExactForKnownData() {
        RollingLatencyWindow w = new RollingLatencyWindow(30_000);
        long t = 100_000 * MS;
        for (int ms = 1; ms <= 100; ms++) w.record(t, ms * MS);
        RollingLatencyWindow.Snapshot s = w.snapshot(t);
        assertThat(s.count()).isEqualTo(100);
        assertThat(s.p50Ms()).isCloseTo(50, within(1.0));
        assertThat(s.p95Ms()).isCloseTo(95, within(1.5));
        assertThat(s.p99Ms()).isCloseTo(99, within(1.5));
    }

    @Test
    void oldSamplesExpireFromTheWindow() {
        RollingLatencyWindow w = new RollingLatencyWindow(30_000);
        long t0 = 100_000 * MS;
        for (int i = 0; i < 100; i++) w.record(t0, 3_000 * MS);            // slow samples at t0
        assertThat(w.snapshot(t0 + 29_000 * MS).p99Ms()).isCloseTo(3000, within(60.0)); // still inside
        for (int i = 0; i < 100; i++) w.record(t0 + 40_000 * MS, 20 * MS); // fast samples 40 s later
        RollingLatencyWindow.Snapshot s = w.snapshot(t0 + 40_000 * MS);
        assertThat(s.count()).isEqualTo(100);                               // slow ones are gone
        assertThat(s.p99Ms()).isCloseTo(20, within(1.0));
    }

    @Test
    void emptyWindowReportsNoData() {
        RollingLatencyWindow w = new RollingLatencyWindow(30_000);
        assertThat(w.snapshot(5_000 * MS).count()).isZero();
        assertThat(w.snapshot(5_000 * MS).p99Ms()).isNaN();
    }

    @Test
    void slopeOfALinearRampIsExact() {
        TailTrendTracker tr = new TailTrendTracker(6, 0.3, 50);
        TailTrend last = null;
        for (int i = 0; i < 6; i++) last = tr.update(i * 5.0, 100, 100 + 10.0 * (i * 5)); // +10 ms per second
        assertThat(last.slopeMsPerSec()).isCloseTo(10.0, within(1e-9));
        assertThat(last.points()).isEqualTo(6);
    }

    @Test
    void flatSeriesHasZeroSlopeAndEwmaEqualsLevel() {
        TailTrendTracker tr = new TailTrendTracker(6, 0.3, 50);
        TailTrend last = null;
        for (int i = 0; i < 8; i++) last = tr.update(i * 5.0, 90, 120);
        assertThat(last.slopeMsPerSec()).isCloseTo(0, within(1e-9));
        assertThat(last.relativeSlopePerSec()).isCloseTo(0, within(1e-9));
        assertThat(last.p99Ewma()).isCloseTo(120, within(1e-9));
    }

    @Test
    void ewmaMovesTowardTheNewLevelByAlpha() {
        TailTrendTracker tr = new TailTrendTracker(6, 0.5, 50);
        tr.update(0, 10, 100);
        TailTrend t = tr.update(5, 10, 200);
        assertThat(t.p99Ewma()).isCloseTo(150, within(1e-9));
    }

    @Test
    void relativeSlopeIsScaleFreeAcrossTimeoutSettings() {
        // the same relative step (x25 over one tick) gives the same relative slope at a 500 ms and a 3000 ms scale
        double rel500 = stepRelativeSlope(20, 500);
        double rel3000 = stepRelativeSlope(120, 3000);
        assertThat(rel500).isCloseTo(rel3000, within(0.01));
        assertThat(rel500).isGreaterThan(0.08); // above the default rise threshold
    }

    private static double stepRelativeSlope(double base, double high) {
        TailTrendTracker tr = new TailTrendTracker(6, 0.3, 5);
        TailTrend last = null;
        for (int i = 0; i < 5; i++) last = tr.update(i * 5.0, base, base);
        last = tr.update(25, high, high);
        return last.relativeSlopePerSec();
    }
}
