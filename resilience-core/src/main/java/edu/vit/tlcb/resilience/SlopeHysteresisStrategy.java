package edu.vit.tlcb.resilience;

import java.util.Locale;

/**
 * The adaptation rule studied in this project.
 *
 * <ul>
 *   <li><b>TIGHTEN</b> (fast, multiplicative): when the relative slope of the p99 is at least
 *       {@code riseSlope}, i.e. the tail is climbing quickly, the threshold is cut to
 *       {@code floor(current * tightenFactor)} (never below the minimum). The breaker
 *       then trips after fewer consecutive failures and sheds a degrading dependency earlier.</li>
 *   <li><b>RELAX</b> (slow, additive): the threshold climbs back toward the baseline by {@code relaxStep}
 *       only after {@code relaxAfterCalmTicks} <em>consecutive</em> calm evaluations. "Calm" means the
 *       relative slope is at most {@code relaxSlope} <em>and</em> the p99 EWMA has fallen to at most
 *       {@code relaxLevelRatio} of the peak seen since tightening, so a degradation that has merely plateaued
 *       does not cause relaxation while the dependency is still bad.</li>
 *   <li><b>Hysteresis:</b> {@code relaxSlope < riseSlope} leaves a dead band in which nothing changes and
 *       the calm counter restarts; combined with the asymmetric step sizes this prevents flapping between
 *       thresholds.</li>
 * </ul>
 * Tighten-fast / relax-slow is the same asymmetry as TCP's multiplicative-decrease / additive-increase.
 */
public final class SlopeHysteresisStrategy implements ThresholdStrategy {

    private final double riseSlope;
    private final double relaxSlope;
    private final double tightenFactor;
    private final int relaxStep;
    private final int relaxAfterCalmTicks;
    private final double relaxLevelRatio;

    private int calmTicks;
    private double peakP99;

    public SlopeHysteresisStrategy(ResilienceSettings.AdaptiveSettings s) {
        this(s.riseSlopeRelPerSec, s.relaxSlopeRelPerSec, s.tightenFactor, s.relaxStep,
                s.relaxAfterCalmTicks, s.relaxLevelRatio);
    }

    public SlopeHysteresisStrategy(double riseSlope, double relaxSlope, double tightenFactor, int relaxStep,
                                   int relaxAfterCalmTicks, double relaxLevelRatio) {
        this.riseSlope = riseSlope;
        this.relaxSlope = relaxSlope;
        this.tightenFactor = tightenFactor;
        this.relaxStep = relaxStep;
        this.relaxAfterCalmTicks = relaxAfterCalmTicks;
        this.relaxLevelRatio = relaxLevelRatio;
    }

    @Override
    public Decision decide(Input in) {
        TailTrend t = in.trend();
        int current = in.currentThreshold();
        int relaxTarget = Math.min(in.baselineThreshold(), in.maxThreshold());

        if (t.relativeSlopePerSec() >= riseSlope) {
            calmTicks = 0;
            peakP99 = Math.max(peakP99, t.p99Ms());
            int target = (int) Math.floor(current * tightenFactor);
            target = Math.max(target, in.minThreshold());
            if (target < current) {
                return new Decision(target, Action.TIGHTEN, String.format(Locale.ROOT,
                        "p99 rising: relative slope %.3f/s >= %.3f/s (p99=%.0f ms, slope=%.1f ms/s)",
                        t.relativeSlopePerSec(), riseSlope, t.p99Ms(), t.slopeMsPerSec()));
            }
            return new Decision(current, Action.HOLD, String.format(Locale.ROOT,
                    "p99 rising (%.3f/s) but already at minimum threshold %d", t.relativeSlopePerSec(), in.minThreshold()));
        }

        if (current >= relaxTarget) {
            calmTicks = 0;
            peakP99 = 0;
            return new Decision(current, Action.HOLD, "at baseline threshold");
        }

        peakP99 = Math.max(peakP99, t.p99Ms());
        if (t.relativeSlopePerSec() > relaxSlope) {
            calmTicks = 0;
            return new Decision(current, Action.HOLD, String.format(Locale.ROOT,
                    "slope %.3f/s inside hysteresis band (%.3f..%.3f)", t.relativeSlopePerSec(), relaxSlope, riseSlope));
        }
        if (t.p99Ewma() > relaxLevelRatio * peakP99) {
            calmTicks = 0;
            return new Decision(current, Action.HOLD, String.format(Locale.ROOT,
                    "slope calm but p99 EWMA %.0f ms still above %.0f%% of peak %.0f ms",
                    t.p99Ewma(), relaxLevelRatio * 100, peakP99));
        }

        calmTicks++;
        if (calmTicks < relaxAfterCalmTicks) {
            return new Decision(current, Action.HOLD, "calm " + calmTicks + "/" + relaxAfterCalmTicks);
        }
        calmTicks = 0;
        int target = Math.min(relaxTarget, current + relaxStep);
        return new Decision(target, Action.RELAX, String.format(Locale.ROOT,
                "tail recovered: slope %.3f/s <= %.3f/s and p99 EWMA %.0f ms <= %.0f%% of peak %.0f ms for %d ticks",
                t.relativeSlopePerSec(), relaxSlope, t.p99Ewma(), relaxLevelRatio * 100, peakP99, relaxAfterCalmTicks));
    }

    @Override
    public void onNoData() {
        calmTicks = 0;
    }

    @Override
    public void reset() {
        calmTicks = 0;
        peakP99 = 0;
    }
}
