package edu.vit.tlcb.resilience;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * A small circuit-breaker state machine whose trip threshold is read <b>live</b> from an
 * {@link IntSupplier} on every failure. It exists because a Resilience4j breaker is immutable: its window
 * size and thresholds are fixed at construction, so the threshold of a running breaker cannot be changed.
 *
 * <h3>Design choice: custom state machine vs. rebuilding the Resilience4j breaker</h3>
 * Two ways to get an adjustable threshold were considered.
 * <ol>
 *   <li><b>Replace the breaker in the registry</b> whenever the threshold changes. Rejected: the new
 *       breaker starts with an empty window (the current failure streak is lost, so adapting would
 *       <em>delay</em> a trip that was about to happen), the OPEN/HALF_OPEN timers and probe permits would
 *       have to be migrated by hand, calls in flight on the old instance report to an orphan, and every
 *       adaptation step would churn objects on the hot path.</li>
 *   <li><b>Own the (tiny) state machine</b>. Chosen: the whole machine is one counter plus three states, the
 *       threshold becomes a plain volatile read, it changes mid-streak with no state loss, and its CLOSED /
 *       OPEN / HALF_OPEN semantics are written to be identical to the Resilience4j configuration used for the
 *       static baseline. {@code Resilience4jBreakerEquivalenceTest} checks that equivalence on random traces
 *       so that any difference measured between "static" and "adaptive" is due to the adaptation and not to
 *       the implementation.</li>
 * </ol>
 * The price is that the adaptive system does not get Resilience4j's event/metrics integrations for free;
 * the equivalent hooks ({@link BreakerListener}) are provided here.
 *
 * <h3>Semantics</h3>
 * <ul>
 *   <li>CLOSED: every success resets the failure streak; a failure increments it and the breaker opens as
 *       soon as {@code streak >= threshold()}. A threshold lowered mid-streak therefore takes effect at the
 *       next failure.</li>
 *   <li>OPEN: calls are rejected until {@code openWait} has elapsed, then the breaker becomes HALF_OPEN
 *       (lazily, on the next permission request or state query).</li>
 *   <li>HALF_OPEN: up to {@code halfOpenProbes} calls are admitted; once that many have completed the
 *       breaker re-opens if all of them failed and closes otherwise (Resilience4j's rule for a 100 %
 *       failure-rate threshold). Results arriving in OPEN (calls started earlier) are ignored.</li>
 * </ul>
 */
public final class ConsecutiveFailureBreaker implements Breaker {

    private final String name;
    private final IntSupplier threshold;
    private final long openWaitNanos;
    private final int halfOpenProbes;
    private final LongSupplier nanoClock;
    private final LongSupplier epochClock;
    private final List<BreakerListener> listeners = new CopyOnWriteArrayList<>();

    private BreakerState state = BreakerState.CLOSED;
    private int streak;
    private long openedAtNanos;
    private int probesAcquired;
    private int probesCompleted;
    private int probesFailed;

    public ConsecutiveFailureBreaker(String name, IntSupplier threshold, long openWaitMs, int halfOpenProbes) {
        this(name, threshold, openWaitMs, halfOpenProbes, System::nanoTime, System::currentTimeMillis);
    }

    /** Constructor with injectable clocks, used by tests to control time. */
    public ConsecutiveFailureBreaker(String name, IntSupplier threshold, long openWaitMs, int halfOpenProbes,
                                     LongSupplier nanoClock, LongSupplier epochClock) {
        this.name = name;
        this.threshold = threshold;
        this.openWaitNanos = openWaitMs * 1_000_000L;
        this.halfOpenProbes = halfOpenProbes;
        this.nanoClock = nanoClock;
        this.epochClock = epochClock;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public synchronized boolean tryAcquirePermission() {
        advanceIfOpenWaitElapsed();
        switch (state) {
            case CLOSED:
                return true;
            case HALF_OPEN:
                if (probesAcquired < halfOpenProbes) {
                    probesAcquired++;
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public synchronized void onSuccess(long durationNanos) {
        switch (state) {
            case CLOSED -> streak = 0;
            case HALF_OPEN -> recordProbe(false);
            default -> { /* OPEN: result of a call started before the breaker opened; ignored */ }
        }
    }

    @Override
    public synchronized void onFailure(long durationNanos, Throwable error) {
        switch (state) {
            case CLOSED -> {
                streak++;
                int t = threshold.getAsInt();
                if (streak >= t) {
                    transition(BreakerState.OPEN, streak + " consecutive failures reached threshold " + t);
                }
            }
            case HALF_OPEN -> recordProbe(true);
            default -> { /* OPEN: ignored */ }
        }
    }

    private void recordProbe(boolean failed) {
        probesCompleted++;
        if (failed) probesFailed++;
        if (probesCompleted >= halfOpenProbes) {
            if (probesFailed >= probesCompleted) {
                transition(BreakerState.OPEN, "all " + probesCompleted + " probe calls failed");
            } else {
                transition(BreakerState.CLOSED, "probe calls succeeded (" + (probesCompleted - probesFailed)
                        + "/" + probesCompleted + ")");
            }
        }
    }

    @Override
    public synchronized void releasePermission() {
        if (state == BreakerState.HALF_OPEN && probesAcquired > probesCompleted) {
            probesAcquired--;
        }
    }

    @Override
    public synchronized BreakerState state() {
        advanceIfOpenWaitElapsed();
        return state;
    }

    @Override
    public int failureThreshold() {
        return threshold.getAsInt();
    }

    @Override
    public synchronized void reset() {
        state = BreakerState.CLOSED;
        streak = 0;
        probesAcquired = 0;
        probesCompleted = 0;
        probesFailed = 0;
    }

    @Override
    public void addListener(BreakerListener listener) {
        listeners.add(listener);
    }

    /** Current failure streak (diagnostics and tests). */
    public synchronized int currentStreak() {
        return streak;
    }

    private void advanceIfOpenWaitElapsed() {
        if (state == BreakerState.OPEN && nanoClock.getAsLong() - openedAtNanos >= openWaitNanos) {
            transition(BreakerState.HALF_OPEN, "open wait elapsed");
        }
    }

    private void transition(BreakerState to, String reason) {
        BreakerState from = state;
        state = to;
        switch (to) {
            case OPEN -> openedAtNanos = nanoClock.getAsLong();
            case HALF_OPEN -> {
                probesAcquired = 0;
                probesCompleted = 0;
                probesFailed = 0;
            }
            case CLOSED -> streak = 0;
            default -> { }
        }
        StateTransition t = new StateTransition(epochClock.getAsLong(), name, from, to, reason);
        for (BreakerListener l : listeners) l.onStateTransition(t);
    }
}
