package edu.vit.tlcb.resilience;

import edu.vit.tlcb.resilience.ThresholdStrategy.Action;
import edu.vit.tlcb.resilience.ThresholdStrategy.Decision;
import edu.vit.tlcb.resilience.ThresholdStrategy.Input;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The strategy as a pure function of {@link TailTrend} inputs: no clocks, no breaker, no threads. */
class SlopeHysteresisStrategyTest {

    private static final int BASELINE = 10;
    private static final int MIN = 2;

    private SlopeHysteresisStrategy strategy;

    @BeforeEach
    void setUp() {
        // rise 0.08/s, relax 0.02/s, tighten x0.5, relax +1 after 3 calm ticks, level ratio 0.5
        strategy = new SlopeHysteresisStrategy(0.08, 0.02, 0.5, 1, 3, 0.5);
    }

    private static TailTrend trend(double relSlope, double p99, double ewma) {
        return new TailTrend(p99 * 0.8, p99, ewma, relSlope * Math.max(p99, 50), relSlope, 6);
    }

    private Decision decide(int current, TailTrend t) {
        return strategy.decide(new Input(t, current, BASELINE, MIN, BASELINE, 500));
    }

    @Test
    void tightensMultiplicativelyWhenP99RisesFast() {
        Decision d = decide(10, trend(0.15, 3000, 800));
        assertThat(d.action()).isEqualTo(Action.TIGHTEN);
        assertThat(d.threshold()).isEqualTo(5);
        assertThat(d.reason()).contains("p99 rising");
        assertThat(decide(5, trend(0.15, 3000, 1500)).threshold()).isEqualTo(2); // floor(2.5) = 2
    }

    @Test
    void neverTightensBelowTheMinimum() {
        Decision d = decide(2, trend(0.5, 3000, 2000));
        assertThat(d.action()).isEqualTo(Action.HOLD);
        assertThat(d.threshold()).isEqualTo(2);
    }

    @Test
    void holdsInsideTheHysteresisBand() {
        decide(10, trend(0.15, 3000, 800)); // tighten once so there is something to relax
        Decision d = decide(5, trend(0.05, 3000, 3000)); // between 0.02 and 0.08
        assertThat(d.action()).isEqualTo(Action.HOLD);
        assertThat(d.reason()).contains("hysteresis band");
    }

    @Test
    void doesNotRelaxWhileTheTailIsMerelyStableButStillHigh() {
        decide(10, trend(0.15, 3000, 800)); // peak = 3000
        for (int i = 0; i < 20; i++) {
            Decision d = decide(5, trend(0.0, 3000, 2900)); // flat plateau, EWMA near peak
            assertThat(d.action()).as("tick %d", i).isEqualTo(Action.HOLD);
        }
    }

    @Test
    void relaxesAfterConsecutiveCalmTicksOnceTheTailHasRecovered() {
        decide(10, trend(0.15, 3000, 800)); // peak = 3000
        TailTrend recovered = trend(-0.1, 40, 300); // falling, EWMA 300 <= 0.5 * 3000
        assertThat(decide(5, recovered).action()).isEqualTo(Action.HOLD);  // calm 1/3
        assertThat(decide(5, recovered).action()).isEqualTo(Action.HOLD);  // calm 2/3
        Decision d = decide(5, recovered);                                  // calm 3/3
        assertThat(d.action()).isEqualTo(Action.RELAX);
        assertThat(d.threshold()).isEqualTo(6);
    }

    @Test
    void anInterruptionRestartsTheCalmCount() {
        decide(10, trend(0.15, 3000, 800));
        TailTrend calm = trend(0.0, 40, 300);
        decide(5, calm);
        decide(5, calm);
        decide(5, trend(0.05, 40, 300)); // back into the band: counter restarts
        assertThat(decide(5, calm).action()).isEqualTo(Action.HOLD);        // calm 1/3 again
        assertThat(decide(5, calm).action()).isEqualTo(Action.HOLD);
        assertThat(decide(5, calm).action()).isEqualTo(Action.RELAX);
    }

    @Test
    void relaxingStopsAtTheBaseline() {
        decide(10, trend(0.15, 3000, 800));
        TailTrend calm = trend(0.0, 40, 100);
        Decision d = null;
        for (int i = 0; i < 3; i++) d = decide(9, calm);
        assertThat(d.threshold()).isEqualTo(10);
        assertThat(decide(10, calm).action()).isEqualTo(Action.HOLD);
        assertThat(decide(10, calm).reason()).contains("baseline");
    }

    @Test
    void noDataResetsCalmProgress() {
        decide(10, trend(0.15, 3000, 800));
        TailTrend calm = trend(0.0, 40, 100);
        decide(5, calm);
        decide(5, calm);
        strategy.onNoData();
        assertThat(decide(5, calm).action()).isEqualTo(Action.HOLD); // would have been the 3rd calm tick
    }
}
