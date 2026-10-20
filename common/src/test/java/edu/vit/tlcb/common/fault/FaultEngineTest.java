package edu.vit.tlcb.common.fault;

import edu.vit.tlcb.common.fault.FaultRule.Distribution;
import edu.vit.tlcb.common.fault.FaultRule.Type;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FaultEngineTest {

    private static FaultRule delay(long ms, double p, Long start, Long end) {
        return new FaultRule("delay", Type.DELAY, p, ms, null, null, null, null, null, null, null, null, start, end, null);
    }

    private static FaultRule error(double p) {
        return new FaultRule("err", Type.ERROR, p, null, null, null, null, null, null, null, null, 500, null, null, null);
    }

    private static FaultRule random(Distribution d, Double min, Double max, Double mean, Double median, Double sigma) {
        return new FaultRule("rnd", Type.RANDOM_DELAY, 1.0, null, d, min, max, mean, median, sigma, 60_000L, null, null, null, null);
    }

    private static FaultEngine engineWith(long seed, FaultRule... rules) {
        FaultEngine e = new FaultEngine();
        e.apply(new FaultSpec(true, seed, 0L, List.of(rules)), 0L);
        return e;
    }

    @Test
    void sameSeedSameKeysGiveIdenticalDecisions() {
        FaultEngine a = engineWith(42, error(0.3), delay(3000, 0.5, null, null));
        FaultEngine b = engineWith(42, error(0.3), delay(3000, 0.5, null, null));
        for (int i = 0; i < 2000; i++) {
            String key = "req-" + i;
            assertThat(a.decide("/api/x", key, 0, 1000)).isEqualTo(b.decide("/api/x", key, 0, 1000));
        }
    }

    @Test
    void decisionDoesNotDependOnArrivalOrder() {
        FaultEngine e = engineWith(7, error(0.5));
        List<Boolean> forward = new ArrayList<>();
        for (int i = 0; i < 500; i++) forward.add(e.decide("/api/x", "k" + i, 0, 0).errorStatus() != null);
        for (int i = 499; i >= 0; i--) {
            assertThat(e.decide("/api/x", "k" + i, 0, 0).errorStatus() != null).isEqualTo(forward.get(i));
        }
    }

    @Test
    void differentSeedsProduceDifferentAssignments() {
        FaultEngine a = engineWith(1, error(0.5));
        FaultEngine b = engineWith(2, error(0.5));
        int differing = 0;
        for (int i = 0; i < 1000; i++) {
            boolean fa = a.decide("/api/x", "k" + i, 0, 0).errorStatus() != null;
            boolean fb = b.decide("/api/x", "k" + i, 0, 0).errorStatus() != null;
            if (fa != fb) differing++;
        }
        assertThat(differing).isGreaterThan(300); // independent coins differ ~50 % of the time
    }

    @Test
    void errorProbabilityIsCalibrated() {
        FaultEngine e = engineWith(42, error(0.30));
        int n = 50_000, errors = 0;
        for (int i = 0; i < n; i++) if (e.decide("/api/x", "k" + i, 0, 0).errorStatus() != null) errors++;
        assertThat(errors / (double) n).isCloseTo(0.30, within(0.01));
    }

    @Test
    void retriesGetIndependentDraws() {
        FaultEngine e = engineWith(42, error(0.5));
        int n = 40_000, bothFail = 0;
        for (int i = 0; i < n; i++) {
            boolean first = e.decide("/api/x", "k" + i, 0, 0).errorStatus() != null;
            boolean second = e.decide("/api/x", "k" + i, 1, 0).errorStatus() != null;
            if (first && second) bothFail++;
        }
        // independent: P(both) = 0.25; fully correlated would be 0.5
        assertThat(bothFail / (double) n).isCloseTo(0.25, within(0.015));
    }

    @Test
    void faultWindowIsRespected() {
        FaultEngine e = engineWith(1, delay(3000, 1.0, 60_000L, 120_000L));
        assertThat(e.decide("/api/x", "k", 0, 59_999).isNone()).isTrue();
        assertThat(e.decide("/api/x", "k", 0, 60_000).delayMs()).isEqualTo(3000);
        assertThat(e.decide("/api/x", "k", 0, 119_999).delayMs()).isEqualTo(3000);
        assertThat(e.decide("/api/x", "k", 0, 120_000).isNone()).isTrue();
    }

    @Test
    void windowIsRelativeToOriginAndOriginDefaultsToApplyTime() {
        FaultEngine e = new FaultEngine();
        e.apply(new FaultSpec(true, 1L, null, List.of(delay(100, 1.0, 1000L, 2000L))), 50_000L);
        assertThat(e.decide("/api/x", "k", 0, 50_500).isNone()).isTrue();
        assertThat(e.decide("/api/x", "k", 0, 51_500).delayMs()).isEqualTo(100);
        assertThat(e.decide("/api/x", "k", 0, 52_000).isNone()).isTrue();
    }

    @Test
    void operationalPathsAreNotMatchedByDefaultPrefix() {
        FaultEngine e = engineWith(1, error(1.0));
        assertThat(e.decide("/health", "k", 0, 0).isNone()).isTrue();
        assertThat(e.decide("/admin/fault", "k", 0, 0).isNone()).isTrue();
        assertThat(e.decide("/api/orders", "k", 0, 0).errorStatus()).isEqualTo(500);
    }

    @Test
    void delayAndErrorCompose() {
        FaultEngine e = engineWith(1, delay(500, 1.0, null, null), error(1.0));
        FaultEngine.Decision d = e.decide("/api/x", "k", 0, 0);
        assertThat(d.delayMs()).isEqualTo(500);
        assertThat(d.errorStatus()).isEqualTo(500);
        assertThat(d.firedRules()).containsExactly("delay", "err");
    }

    @Test
    void disabledOrClearedEngineInjectsNothing() {
        FaultEngine e = engineWith(1, error(1.0));
        e.clear();
        assertThat(e.decide("/api/x", "k", 0, 0).isNone()).isTrue();
    }

    @Test
    void uniformDelayStaysInRange() {
        FaultEngine e = engineWith(3, random(Distribution.UNIFORM, 100.0, 300.0, null, null, null));
        for (int i = 0; i < 5000; i++) {
            assertThat(e.decide("/api/x", "k" + i, 0, 0).delayMs()).isBetween(100L, 300L);
        }
    }

    @Test
    void exponentialDelayHasRequestedMean() {
        FaultEngine e = engineWith(3, random(Distribution.EXPONENTIAL, null, null, 200.0, null, null));
        double sum = 0;
        int n = 40_000;
        for (int i = 0; i < n; i++) sum += e.decide("/api/x", "k" + i, 0, 0).delayMs();
        assertThat(sum / n).isCloseTo(200.0, within(6.0));
    }

    @Test
    void lognormalDelayHasRequestedMedian() {
        FaultEngine e = engineWith(3, random(Distribution.LOGNORMAL, null, null, null, 150.0, 0.5));
        List<Long> xs = new ArrayList<>();
        for (int i = 0; i < 20_001; i++) xs.add(e.decide("/api/x", "k" + i, 0, 0).delayMs());
        xs.sort(Long::compare);
        assertThat((double) xs.get(10_000)).isCloseTo(150.0, within(6.0));
    }

    @Test
    void invalidSpecsAreRejected() {
        assertThat(new FaultSpec(true, 1L, null, List.of(error(1.5))).validate()).contains("probability");
        assertThat(new FaultSpec(true, 1L, null, List.of(delay(10, 1.0, 5000L, 1000L))).validate()).contains("endOffsetMs");
        assertThat(new FaultSpec(true, 1L, null, List.of(delay(-1, 1.0, null, null))).validate()).contains("delayMs");
        assertThat(new FaultSpec(true, 1L, null, List.of(delay(10, 1.0, null, null))).validate()).isNull();
    }
}
