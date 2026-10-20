package edu.vit.tlcb.resilience;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Turns the stream of per-evaluation window percentiles into a {@link TailTrend}: an EWMA of the p99 and an
 * ordinary-least-squares slope over the last {@code points} evaluations.
 */
public final class TailTrendTracker {

    private record Point(double tSec, double p99Ms) {
    }

    private final int maxPoints;
    private final double alpha;
    private final double floorMs;
    private final Deque<Point> points = new ArrayDeque<>();
    private double ewma = Double.NaN;

    public TailTrendTracker(int maxPoints, double ewmaAlpha, double slopeFloorMs) {
        this.maxPoints = maxPoints;
        this.alpha = ewmaAlpha;
        this.floorMs = slopeFloorMs;
    }

    public TailTrend update(double tSec, double p95Ms, double p99Ms) {
        ewma = Double.isNaN(ewma) ? p99Ms : alpha * p99Ms + (1 - alpha) * ewma;
        points.addLast(new Point(tSec, p99Ms));
        while (points.size() > maxPoints) points.removeFirst();

        double slope = 0;
        double relative = 0;
        int n = points.size();
        if (n >= 3) {
            double meanT = 0;
            double meanY = 0;
            for (Point p : points) {
                meanT += p.tSec();
                meanY += p.p99Ms();
            }
            meanT /= n;
            meanY /= n;
            double num = 0;
            double den = 0;
            for (Point p : points) {
                num += (p.tSec() - meanT) * (p.p99Ms() - meanY);
                den += (p.tSec() - meanT) * (p.tSec() - meanT);
            }
            slope = den == 0 ? 0 : num / den;
            relative = slope / Math.max(meanY, floorMs);
        }
        return new TailTrend(p95Ms, p99Ms, ewma, slope, relative, n);
    }

    public void reset() {
        points.clear();
        ewma = Double.NaN;
    }
}
