package edu.vit.tlcb.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The control loop of the adaptive breaker: on every {@link #evaluate()} it reads the rolling latency window,
 * updates the tail trend, asks the {@link ThresholdStrategy} what to do and applies the (clamped) result to a
 * volatile threshold that the breaker reads on every failure.
 *
 * <p>Every <em>change</em> is appended to an audit log ({@link AdaptationEvent}) and logged at INFO with its
 * reason; every <em>evaluation</em> (change or not) is appended to a trace so the whole trajectory of
 * p99, trend and threshold can be plotted afterwards.
 *
 * <p>All time comes from injected clocks, so tests can replay synthetic traces deterministically.
 */
public final class AdaptiveThresholdController {

    /** One evaluation tick, whether or not it changed the threshold. */
    public record TracePoint(long epochMs, double tSec, int threshold, double p95Ms, double p99Ms, double p99Ewma,
                             double slopeMsPerSec, double relativeSlopePerSec, long samples, String action) {
    }

    private static final Logger log = LoggerFactory.getLogger(AdaptiveThresholdController.class);
    private static final int MAX_TRACE = 5000;

    private final String name;
    private final int baseline;
    private final int min;
    private final int max;
    private final int minSamples;
    private final RollingLatencyWindow window;
    private final TailTrendTracker tracker;
    private final ThresholdStrategy strategy;
    private final LongSupplier nanoClock;
    private final LongSupplier epochClock;
    private volatile long originEpochMs;

    private final List<AdaptationEvent> events = new ArrayList<>();
    private final List<TracePoint> trace = new ArrayList<>();
    private final List<Consumer<AdaptationEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile int threshold;

    public AdaptiveThresholdController(String name, int baseline, ResilienceSettings.AdaptiveSettings s,
                                       RollingLatencyWindow window, ThresholdStrategy strategy,
                                       LongSupplier nanoClock, LongSupplier epochClock) {
        this.name = name;
        this.baseline = baseline;
        this.min = Math.min(s.minThreshold, baseline);
        this.max = s.maxThreshold <= 0 ? baseline : Math.max(s.maxThreshold, baseline);
        this.minSamples = s.minSamples;
        this.window = window;
        this.strategy = strategy;
        this.tracker = new TailTrendTracker(s.trendPoints, s.ewmaAlpha, s.slopeFloorMs);
        this.nanoClock = nanoClock;
        this.epochClock = epochClock;
        this.originEpochMs = epochClock.getAsLong();
        this.threshold = baseline;
    }

    /** The trip threshold currently in force (consecutive failures to open). */
    public int threshold() {
        return threshold;
    }

    public int baseline() {
        return baseline;
    }

    public void addListener(Consumer<AdaptationEvent> l) {
        listeners.add(l);
    }

    /** Runs one adaptation step. Safe to call from a scheduler thread. */
    public synchronized void evaluate() {
        long nowNanos = nanoClock.getAsLong();
        long epoch = epochClock.getAsLong();
        double tSec = (epoch - originEpochMs) / 1000.0;
        RollingLatencyWindow.Snapshot w = window.snapshot(nowNanos);

        if (w.count() < minSamples) {
            strategy.onNoData();
            addTrace(new TracePoint(epoch, tSec, threshold, w.p95Ms(), w.p99Ms(), Double.NaN, Double.NaN, Double.NaN,
                    w.count(), "NO_DATA"));
            return;
        }

        TailTrend trend = tracker.update(tSec, w.p95Ms(), w.p99Ms());
        ThresholdStrategy.Decision d = strategy.decide(new ThresholdStrategy.Input(
                trend, threshold, baseline, min, max, w.count()));
        int proposed = Math.max(min, Math.min(max, d.threshold()));
        ThresholdStrategy.Action action = proposed == threshold ? ThresholdStrategy.Action.HOLD : d.action();

        addTrace(new TracePoint(epoch, tSec, proposed, trend.p95Ms(), trend.p99Ms(), trend.p99Ewma(),
                trend.slopeMsPerSec(), trend.relativeSlopePerSec(), w.count(), action.name()));

        if (proposed != threshold) {
            AdaptationEvent e = new AdaptationEvent(epoch, tSec, name, action, threshold, proposed, d.reason(),
                    trend.p95Ms(), trend.p99Ms(), trend.p99Ewma(), trend.slopeMsPerSec(),
                    trend.relativeSlopePerSec(), w.count());
            log.info("ADAPT breaker={} {} threshold {} -> {} | {} | p95={}ms p99={}ms ewma={}ms slope={}ms/s n={}",
                    name, action, threshold, proposed, d.reason(), Math.round(trend.p95Ms()),
                    Math.round(trend.p99Ms()), Math.round(trend.p99Ewma()),
                    Math.round(trend.slopeMsPerSec()), w.count());
            threshold = proposed;
            synchronized (events) {
                events.add(e);
            }
            for (Consumer<AdaptationEvent> l : listeners) l.accept(e);
        }
    }

    private void addTrace(TracePoint p) {
        synchronized (trace) {
            if (trace.size() < MAX_TRACE) trace.add(p);
        }
    }

    /** Immutable copy of the audit log. */
    public List<AdaptationEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /** Immutable copy of the per-evaluation trace. */
    public List<TracePoint> trace() {
        synchronized (trace) {
            return List.copyOf(trace);
        }
    }

    /**
     * Starts a new measurement period without disturbing the control state: the audit log and trace are
     * cleared and their time axis restarts at {@code originEpochMs}, but the threshold, window and trend are
     * kept (the controller keeps adapting across the warm-up to measurement boundary).
     */
    public synchronized void clearHistory(long originEpochMs) {
        this.originEpochMs = originEpochMs;
        synchronized (events) {
            events.clear();
        }
        synchronized (trace) {
            trace.clear();
        }
    }

    /** Back to baseline with empty history (new configuration). */
    public synchronized void reset() {
        window.reset();
        tracker.reset();
        strategy.reset();
        threshold = baseline;
        synchronized (events) {
            events.clear();
        }
        synchronized (trace) {
            trace.clear();
        }
    }
}
