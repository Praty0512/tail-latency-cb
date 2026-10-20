package edu.vit.tlcb.resilience;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

/** Creates the breaker for a given {@link ResilienceSettings.SystemType}. */
public final class BreakerFactory {

    private BreakerFactory() {
    }

    public static Breaker create(String name, ResilienceSettings settings, ScheduledExecutorService scheduler) {
        return switch (settings.system) {
            case NONE -> new NoopBreaker(name);
            case STATIC -> new Resilience4jBreaker(name, settings.failureThreshold,
                    Duration.ofMillis(settings.openWaitMs), settings.halfOpenProbes, true);
            case ADAPTIVE -> TailLatencyAdaptiveBreaker.create(name, settings, scheduler);
        };
    }
}
