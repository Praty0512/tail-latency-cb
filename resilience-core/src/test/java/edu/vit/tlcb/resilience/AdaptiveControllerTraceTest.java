package edu.vit.tlcb.resilience;

import edu.vit.tlcb.resilience.ThresholdStrategy.Action;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feeds <b>synthetic latency traces</b> through the real window, trend tracker, strategy and controller on a
 * simulated clock (no sleeping, no randomness other than a seeded jitter) and asserts how the trip threshold
 * moves. Each test describes the trace in terms of "latency in ms as a function of the second".
 */
class AdaptiveControllerTraceTest {

    private static final long MS = 1_000_000L;
    private static final int SAMPLES_PER_SEC = 50;

    private final FakeTime time = new FakeTime();

    /** Result of replaying a trace. */
    private record Replay(AdaptiveThresholdController controller, List<Integer> thresholdPerTick) {
    }

    private static ResilienceSettings settings(int baseline) {
        ResilienceSettings s = new ResilienceSettings();
        s.failureThreshold = baseline;
        s.adaptive.minThreshold = 2;
        return s;
    }

    /**
     * Replays {@code seconds} of traffic. {@code latencyMs} gives, for each second, the latency of the
     * sample with index i of that second (so a trace can mix slow and fast calls inside one second).
     */
    private Replay replay(ResilienceSettings s, int seconds, SampleFn latencyMs) {
        RollingLatencyWindow window = new RollingLatencyWindow(s.adaptive.windowMs);
        AdaptiveThresholdController c = new AdaptiveThresholdController("t", s.failureThreshold, s.adaptive, window,
                new SlopeHysteresisStrategy(s.adaptive), time::nanos, time::epochMs);
        List<Integer> perTick = new ArrayList<>();
        Random jitter = new Random(1);
        for (int sec = 0; sec < seconds; sec++) {
            for (int i = 0; i < SAMPLES_PER_SEC; i++) {
                double ms = latencyMs.at(sec, i) * (1 + 0.1 * (jitter.nextDouble() - 0.5));
                window.record(time.nanos(), (long) (ms * MS));
            }
            time.advanceSec(1);
            if ((sec + 1) % (s.adaptive.evalIntervalMs / 1000) == 0) {
                c.evaluate();
                perTick.add(c.threshold());
            }
        }
        return new Replay(c, perTick);
    }

    @FunctionalInterface
    private interface SampleFn {
        double at(int second, int index);
    }

    private static SampleFn constant(double ms) {
        return (sec, i) -> ms;
    }

    /** Half of the calls take {@code slowMs} (like the 3 s delay fault on 50 % of requests), the rest 20 ms. */
    private static SampleFn halfSlow(double slowMs) {
        return (sec, i) -> i % 2 == 0 ? slowMs : 20;
    }

    private static SampleFn phases(int stepAtSec, int recoverAtSec, SampleFn degraded) {
        return (sec, i) -> sec >= stepAtSec && sec < recoverAtSec ? degraded.at(sec, i) : 20;
    }

    @Test
    void stableLatencyNeverMovesTheThreshold() {
        Replay r = replay(settings(10), 300, constant(20));
        assertThat(r.controller().events()).isEmpty();
        assertThat(r.thresholdPerTick()).containsOnly(10);
    }

    @Test
    void aSlowCreepBelowTheSlopeThresholdDoesNotTighten() {
        // p99 drifts from 20 ms to 60 ms over 4 minutes: real, but far from a "rising fast" signal
        Replay r = replay(settings(10), 240, (sec, i) -> 20 + 40.0 * sec / 240);
        assertThat(r.controller().events()).isEmpty();
    }

    @Test
    void aSuddenRiseOfTheTailTightensTheThresholdWithinTwoEvaluations() {
        Replay r = replay(settings(10), 90, phases(60, 1000, halfSlow(3000)));
        // before the fault nothing happens
        assertThat(r.thresholdPerTick().subList(0, 12)).containsOnly(10);
        // fault begins at t=60 s; evaluations at 65 s and 70 s must have cut 10 -> 5 -> 2
        assertThat(r.thresholdPerTick().get(12)).isEqualTo(5);
        assertThat(r.thresholdPerTick().get(13)).isEqualTo(2);
        assertThat(r.controller().events()).extracting(AdaptationEvent::action)
                .containsExactly(Action.TIGHTEN, Action.TIGHTEN);
        assertThat(r.controller().events().get(0).reason()).contains("p99 rising");
    }

    @Test
    void theSameRelativeRiseTightensAtEveryTimeoutScale() {
        // a 500 ms-timeout system sees its p99 saturate at 500 ms; the adaptation must still react
        Replay r = replay(settings(10), 90, phases(60, 1000, halfSlow(500)));
        assertThat(r.controller().threshold()).isEqualTo(2);
    }

    @Test
    void aSustainedPlateauKeepsTheBreakerTight() {
        Replay r = replay(settings(10), 180, phases(60, 1000, halfSlow(3000)));
        // from the second tightening on, 100 s of high-but-flat tail latency: no relaxing, no flapping
        List<Integer> after = r.thresholdPerTick().subList(14, r.thresholdPerTick().size());
        assertThat(after).containsOnly(2);
        assertThat(r.controller().events()).hasSize(2);
    }

    @Test
    void recoveryRelaxesGraduallyBackToTheBaselineAndNeverAbove() {
        // degraded from 60 s to 180 s, healthy afterwards
        Replay r = replay(settings(10), 420, phases(60, 180, halfSlow(3000)));
        List<AdaptationEvent> events = r.controller().events();

        List<AdaptationEvent> relaxes = events.stream().filter(e -> e.action() == Action.RELAX).toList();
        assertThat(relaxes).isNotEmpty();
        // no relaxing before the degraded samples have left the 30 s window
        assertThat(relaxes.get(0).tSec()).isGreaterThan(180 + 30 - 1);
        // each relax step is +1 and never exceeds the baseline
        assertThat(relaxes).allSatisfy(e -> assertThat(e.newThreshold() - e.oldThreshold()).isEqualTo(1));
        assertThat(r.thresholdPerTick()).allSatisfy(t -> assertThat(t).isBetween(2, 10));
        assertThat(r.controller().threshold()).isEqualTo(10);
        // no tightening after recovery began
        assertThat(events.stream().filter(e -> e.tSec() > 180).map(AdaptationEvent::action)).doesNotContain(Action.TIGHTEN);
    }

    @Test
    void relaxStepsAreSpacedByTheCalmRequirement() {
        Replay r = replay(settings(10), 420, phases(60, 180, halfSlow(3000)));
        List<AdaptationEvent> relaxes = r.controller().events().stream().filter(e -> e.action() == Action.RELAX).toList();
        for (int i = 1; i < relaxes.size(); i++) {
            double gap = relaxes.get(i).tSec() - relaxes.get(i - 1).tSec();
            assertThat(gap).as("gap between relax step %d and %d", i - 1, i).isGreaterThanOrEqualTo(15.0 - 0.01); // 3 ticks x 5 s
        }
    }

    @Test
    void repeatedFaultEpisodesDoNotCauseFlapping() {
        // two degradations separated by recovery; threshold direction may reverse only around genuine changes
        SampleFn episodes = (sec, i) -> ((sec >= 60 && sec < 120) || (sec >= 300 && sec < 360)) ? halfSlow(3000).at(sec, i) : 20;
        Replay r = replay(settings(10), 560, episodes);
        List<Action> actions = r.controller().events().stream().map(AdaptationEvent::action).toList();
        int reversals = 0;
        for (int i = 1; i < actions.size(); i++) if (actions.get(i) != actions.get(i - 1)) reversals++;
        // TIGHTEN,TIGHTEN | RELAX x8 | TIGHTEN,TIGHTEN | RELAX... => 3 reversals for two episodes
        assertThat(reversals).isLessThanOrEqualTo(4);
        assertThat(r.controller().threshold()).isEqualTo(10);
    }

    @Test
    void thresholdIsClampedToTheMinimumAndBaseline() {
        ResilienceSettings s = settings(3);       // baseline 3, min 2
        Replay r = replay(s, 120, phases(30, 1000, halfSlow(3000)));
        assertThat(r.thresholdPerTick()).allSatisfy(t -> assertThat(t).isBetween(2, 3));
        assertThat(r.controller().threshold()).isEqualTo(2); // floor(3*0.5)=1 clamped up to the minimum
    }

    @Test
    void tooFewSamplesInTheWindowSkipsTheEvaluation() {
        ResilienceSettings s = settings(10);
        RollingLatencyWindow window = new RollingLatencyWindow(s.adaptive.windowMs);
        AdaptiveThresholdController c = new AdaptiveThresholdController("t", 10, s.adaptive, window,
                new SlopeHysteresisStrategy(s.adaptive), time::nanos, time::epochMs);
        for (int i = 0; i < 5; i++) window.record(time.nanos(), 3000 * MS); // 5 samples < minSamples (20)
        c.evaluate();
        assertThat(c.threshold()).isEqualTo(10);
        assertThat(c.trace()).hasSize(1);
        assertThat(c.trace().get(0).action()).isEqualTo("NO_DATA");
    }

    @Test
    void everyChangeIsAuditedWithItsEvidence() {
        Replay r = replay(settings(10), 90, phases(60, 1000, halfSlow(3000)));
        AdaptationEvent e = r.controller().events().get(0);
        assertThat(e.breaker()).isEqualTo("t");
        assertThat(e.oldThreshold()).isEqualTo(10);
        assertThat(e.newThreshold()).isEqualTo(5);
        assertThat(e.p99Ms()).isGreaterThan(2000);
        assertThat(e.relativeSlopePerSec()).isGreaterThanOrEqualTo(0.08);
        assertThat(e.reason()).isNotBlank();
        assertThat(e.windowSamples()).isGreaterThan(1000);
        // the trace holds one point per evaluation, changes included
        assertThat(r.controller().trace()).hasSize(18);
    }

    @Test
    void fixedStrategyIsTheNoAdaptationAblation() {
        ResilienceSettings s = settings(10);
        RollingLatencyWindow window = new RollingLatencyWindow(s.adaptive.windowMs);
        AdaptiveThresholdController c = new AdaptiveThresholdController("t", 10, s.adaptive, window,
                new FixedThresholdStrategy(), time::nanos, time::epochMs);
        IntToDoubleFunction fault = sec -> sec >= 60 ? 3000 : 20;
        for (int sec = 0; sec < 150; sec++) {
            for (int i = 0; i < SAMPLES_PER_SEC; i++) window.record(time.nanos(), (long) (fault.applyAsDouble(sec) * MS));
            time.advanceSec(1);
            if ((sec + 1) % 5 == 0) c.evaluate();
        }
        assertThat(c.threshold()).isEqualTo(10);
        assertThat(c.events()).isEmpty();
    }
}
