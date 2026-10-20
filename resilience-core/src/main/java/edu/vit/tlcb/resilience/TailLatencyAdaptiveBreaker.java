package edu.vit.tlcb.resilience;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * <b>The adaptive circuit breaker.</b> A {@link ConsecutiveFailureBreaker} whose trip threshold is owned by an
 * {@link AdaptiveThresholdController}. Every call outcome that reaches the breaker (success or failure,
 * including timeouts) also feeds the rolling latency window; a scheduled evaluation then tightens the
 * threshold when the p99 trend is rising and relaxes it back toward the baseline once the tail has
 * recovered. Fast-fail rejections never reach the window: they carry no information about the dependency's
 * latency and would otherwise make an open breaker look like a healed one.
 */
public final class TailLatencyAdaptiveBreaker implements Breaker {

    private final ConsecutiveFailureBreaker core;
    private final AdaptiveThresholdController controller;
    private final RollingLatencyWindow window;
    private final LongSupplier nanoClock;
    private ScheduledFuture<?> evaluation;

    /**
     * @param scheduler if non-null, {@code controller.evaluate()} is scheduled every {@code evalIntervalMs};
     *                  pass null in tests and call {@link #controller()}{@code .evaluate()} manually
     */
    public TailLatencyAdaptiveBreaker(String name, ResilienceSettings settings, ThresholdStrategy strategy,
                                      ScheduledExecutorService scheduler,
                                      LongSupplier nanoClock, LongSupplier epochClock) {
        ResilienceSettings.AdaptiveSettings a = settings.adaptive;
        this.nanoClock = nanoClock;
        this.window = new RollingLatencyWindow(a.windowMs);
        this.controller = new AdaptiveThresholdController(name, settings.failureThreshold, a, window, strategy,
                nanoClock, epochClock);
        this.core = new ConsecutiveFailureBreaker(name, controller::threshold, settings.openWaitMs,
                settings.halfOpenProbes, nanoClock, epochClock);
        if (scheduler != null) {
            // Resilience4j moves OPEN -> HALF_OPEN with a timer; the custom machine would otherwise do it lazily on
            // the next call. Poke it when the open wait is over so that the transition (and its timestamp) is
            // timer-accurate even when no traffic reaches this breaker, exactly like the static baseline.
            long wait = settings.openWaitMs;
            core.addListener(t -> {
                if (t.to() == BreakerState.OPEN) {
                    scheduler.schedule(core::state, wait + 1, TimeUnit.MILLISECONDS);
                }
            });
            this.evaluation = scheduler.scheduleAtFixedRate(this::safeEvaluate, a.evalIntervalMs, a.evalIntervalMs,
                    TimeUnit.MILLISECONDS);
        }
    }

    public static TailLatencyAdaptiveBreaker create(String name, ResilienceSettings settings,
                                                    ScheduledExecutorService scheduler) {
        ThresholdStrategy strategy = "fixed".equalsIgnoreCase(settings.adaptive.strategy)
                ? new FixedThresholdStrategy()
                : new SlopeHysteresisStrategy(settings.adaptive);
        return new TailLatencyAdaptiveBreaker(name, settings, strategy, scheduler,
                System::nanoTime, System::currentTimeMillis);
    }

    private void safeEvaluate() {
        try {
            controller.evaluate();
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(TailLatencyAdaptiveBreaker.class)
                    .warn("adaptation step failed for {}", core.name(), e);
        }
    }

    public AdaptiveThresholdController controller() {
        return controller;
    }

    public List<AdaptationEvent> adaptationEvents() {
        return controller.events();
    }

    @Override
    public String name() {
        return core.name();
    }

    @Override
    public boolean tryAcquirePermission() {
        return core.tryAcquirePermission();
    }

    @Override
    public void onSuccess(long durationNanos) {
        window.record(nanoClock.getAsLong(), durationNanos);
        core.onSuccess(durationNanos);
    }

    @Override
    public void onFailure(long durationNanos, Throwable error) {
        window.record(nanoClock.getAsLong(), durationNanos);
        core.onFailure(durationNanos, error);
    }

    @Override
    public void releasePermission() {
        core.releasePermission();
    }

    @Override
    public BreakerState state() {
        return core.state();
    }

    @Override
    public int failureThreshold() {
        return controller.threshold();
    }

    @Override
    public void reset() {
        core.reset();
        controller.reset();
    }

    @Override
    public void addListener(BreakerListener listener) {
        core.addListener(listener);
    }

    @Override
    public void close() {
        if (evaluation != null) evaluation.cancel(false);
    }
}
