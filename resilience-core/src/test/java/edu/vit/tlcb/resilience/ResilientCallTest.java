package edu.vit.tlcb.resilience;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Retry + breaker + TimeLimiter composition, run against fake downstreams on a real (small) scheduler. */
class ResilientCallTest {

    private ScheduledThreadPoolExecutor scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ScheduledThreadPoolExecutor(2);
        scheduler.setRemoveOnCancelPolicy(true);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private static ResilienceSettings settings(ResilienceSettings.SystemType sys, long timeoutMs, int retries, int threshold) {
        ResilienceSettings s = new ResilienceSettings();
        s.system = sys;
        s.timeoutMs = timeoutMs;
        s.retries = retries;
        s.retryWaitMs = 1;
        s.failureThreshold = threshold;
        s.openWaitMs = 200;
        s.halfOpenProbes = 1;
        return s;
    }

    private ResilientCall call(ResilienceSettings s) {
        return new ResilientCall("dep", s, BreakerFactory.create("dep", s, scheduler), scheduler, null);
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        try {
            return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }

    private static CompletionStage<String> failing(int status) {
        return CompletableFuture.failedFuture(new DownstreamException(status, "boom"));
    }

    @Test
    void retriesUpToTheConfiguredCountAndThenGivesUp() {
        AtomicInteger attempts = new AtomicInteger();
        ResilientCall c = call(settings(ResilienceSettings.SystemType.NONE, 1000, 3, 5));
        assertThatThrownBy(() -> await(c.execute(a -> {
            attempts.incrementAndGet();
            return failing(500);
        }))).isInstanceOf(DownstreamException.class);
        assertThat(attempts.get()).isEqualTo(4); // 1 try + 3 retries
    }

    @Test
    void retriesStopAsSoonAsAnAttemptSucceeds() throws Exception {
        List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
        ResilientCall c = call(settings(ResilienceSettings.SystemType.NONE, 1000, 3, 5));
        String r = await(c.execute(attempt -> {
            seen.add(attempt);
            return attempt < 2 ? failing(500) : CompletableFuture.completedFuture("ok");
        }));
        assertThat(r).isEqualTo("ok");
        assertThat(seen).containsExactly(0, 1, 2); // attempt numbers are passed through (used for fault independence)
    }

    @Test
    void aSlowAttemptIsCutOffByTheTimeLimiter() {
        ResilientCall c = call(settings(ResilienceSettings.SystemType.NONE, 100, 0, 5));
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> await(c.execute(a -> new CompletableFuture<String>()))) // never completes
                .isInstanceOf(TimeoutException.class);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).isBetween(90L, 1500L);
    }

    @Test
    void aTimedOutAttemptIsRetried() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ResilientCall c = call(settings(ResilienceSettings.SystemType.NONE, 100, 1, 5));
        String r = await(c.execute(a -> attempts.getAndIncrement() == 0
                ? new CompletableFuture<String>()
                : CompletableFuture.completedFuture("ok")));
        assertThat(r).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void theTimeLimiterCancelsTheRunningAttempt() {
        CompletableFuture<String> hung = new CompletableFuture<>();
        ResilientCall c = call(settings(ResilienceSettings.SystemType.NONE, 50, 0, 5));
        assertThatThrownBy(() -> await(c.execute(a -> hung))).isInstanceOf(TimeoutException.class);
        assertThat(hung.isCancelled()).isTrue(); // so the HTTP client aborts the request
    }

    @Test
    void staticBreakerOpensAfterNConsecutiveFailuresAndFailsFast() {
        AtomicInteger downstreamCalls = new AtomicInteger();
        ResilientCall c = call(settings(ResilienceSettings.SystemType.STATIC, 1000, 0, 3));
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> await(c.execute(a -> {
                downstreamCalls.incrementAndGet();
                return failing(500);
            }))).isInstanceOf(DownstreamException.class);
        }
        assertThat(c.breaker().state()).isEqualTo(BreakerState.OPEN);
        assertThatThrownBy(() -> await(c.execute(a -> {
            downstreamCalls.incrementAndGet();
            return failing(500);
        }))).isInstanceOf(BreakerOpenException.class);
        assertThat(downstreamCalls.get()).isEqualTo(3); // the rejected call never reached the downstream
    }

    @Test
    void aBreakerRejectionIsNotRetried() {
        AtomicInteger downstreamCalls = new AtomicInteger();
        ResilientCall c = call(settings(ResilienceSettings.SystemType.STATIC, 1000, 3, 1));
        assertThatThrownBy(() -> await(c.execute(a -> {
            downstreamCalls.incrementAndGet();
            return failing(500);
        }))).isInstanceOf(Exception.class);
        // threshold 1: the first failure opens the breaker, so the remaining retries are rejected, not attempted
        assertThat(downstreamCalls.get()).isEqualTo(1);
    }

    @Test
    void breakerRecoversThroughHalfOpenProbe() throws Exception {
        ResilientCall c = call(settings(ResilienceSettings.SystemType.STATIC, 1000, 0, 2));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> await(c.execute(a -> failing(500)))).isInstanceOf(DownstreamException.class);
        }
        assertThat(c.breaker().state()).isEqualTo(BreakerState.OPEN);
        Thread.sleep(350); // open wait is 200 ms
        assertThat(c.breaker().state()).isEqualTo(BreakerState.HALF_OPEN);
        String probe = await(c.<String>execute(a -> CompletableFuture.completedFuture("ok")));
        assertThat(probe).isEqualTo("ok");
        assertThat(c.breaker().state()).isEqualTo(BreakerState.CLOSED);
    }

    @Test
    void noBreakerSystemNeverRejects() {
        ResilientCall c = call(settings(ResilienceSettings.SystemType.NONE, 1000, 0, 1));
        for (int i = 0; i < 50; i++) {
            assertThatThrownBy(() -> await(c.execute(a -> failing(500)))).isInstanceOf(DownstreamException.class);
        }
        assertThat(c.breaker().state()).isEqualTo(BreakerState.NONE);
    }

    @Test
    void istioStyleRetryPolicyDoesNotRetryPlain500() {
        AtomicInteger attempts = new AtomicInteger();
        ResilienceSettings s = settings(ResilienceSettings.SystemType.NONE, 1000, 3, 5);
        s.retryOn = ResilienceSettings.RetryPolicy.ISTIO_GATEWAY_ERROR;
        ResilientCall c = call(s);
        assertThatThrownBy(() -> await(c.execute(a -> {
            attempts.incrementAndGet();
            return failing(500);
        }))).isInstanceOf(DownstreamException.class);
        assertThat(attempts.get()).isEqualTo(1);
        attempts.set(0);
        assertThatThrownBy(() -> await(c.execute(a -> {
            attempts.incrementAndGet();
            return failing(503);
        }))).isInstanceOf(DownstreamException.class);
        assertThat(attempts.get()).isEqualTo(4);
    }

    @Test
    void adaptiveBreakerTripsEarlierOnceTheThresholdHasBeenTightened() throws Exception {
        ResilienceSettings s = settings(ResilienceSettings.SystemType.ADAPTIVE, 1000, 0, 8);
        s.adaptive.evalIntervalMs = 3_600_000; // evaluation is driven manually below
        TailLatencyAdaptiveBreaker b = (TailLatencyAdaptiveBreaker) BreakerFactory.create("dep", s, scheduler);
        ResilientCall c = new ResilientCall("dep", s, b, scheduler, null);
        assertThat(b.failureThreshold()).isEqualTo(8);

        // 6 failures do not open a baseline-8 breaker
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> await(c.execute(a -> failing(500)))).isInstanceOf(DownstreamException.class);
        }
        assertThat(b.state()).isEqualTo(BreakerState.CLOSED);
        b.reset();

        // simulate the controller having tightened to 4: the 4th consecutive failure opens it
        FakeTime time = new FakeTime();
        ResilienceSettings s2 = settings(ResilienceSettings.SystemType.ADAPTIVE, 1000, 0, 8);
        TailLatencyAdaptiveBreaker tight = new TailLatencyAdaptiveBreaker("dep2", s2,
                new ForcedThresholdStrategy(4), null, time::nanos, time::epochMs);
        for (int i = 0; i < 40; i++) tight.onSuccess(20_000_000L); // give the window enough samples
        tight.controller().evaluate();
        assertThat(tight.failureThreshold()).isEqualTo(4);
        for (int i = 0; i < 3; i++) {
            tight.tryAcquirePermission();
            tight.onFailure(1, new RuntimeException());
        }
        assertThat(tight.state()).isEqualTo(BreakerState.CLOSED);
        tight.tryAcquirePermission();
        tight.onFailure(1, new RuntimeException());
        assertThat(tight.state()).isEqualTo(BreakerState.OPEN);
    }

    @Test
    void adaptiveBreakerLeavesOpenOnATimerEvenWithoutTraffic() throws Exception {
        ResilienceSettings s = settings(ResilienceSettings.SystemType.ADAPTIVE, 1000, 0, 2);
        s.openWaitMs = 100;
        s.adaptive.evalIntervalMs = 3_600_000;
        TailLatencyAdaptiveBreaker b = (TailLatencyAdaptiveBreaker) BreakerFactory.create("dep", s, scheduler);
        List<BreakerState> seen = Collections.synchronizedList(new ArrayList<>());
        b.addListener(t -> seen.add(t.to()));
        for (int i = 0; i < 2; i++) {
            b.tryAcquirePermission();
            b.onFailure(1, new RuntimeException());
        }
        assertThat(seen).containsExactly(BreakerState.OPEN);
        Thread.sleep(400); // no call, no state() query: only the timer can move it
        assertThat(seen).containsExactly(BreakerState.OPEN, BreakerState.HALF_OPEN);
    }

    /** Test strategy that always asks for a fixed threshold. */
    private static final class ForcedThresholdStrategy implements ThresholdStrategy {
        private final int target;

        ForcedThresholdStrategy(int target) {
            this.target = target;
        }

        @Override
        public Decision decide(Input input) {
            return new Decision(target, Action.TIGHTEN, "forced for test");
        }

        @Override
        public void reset() {
        }
    }

    @Test
    void settingsValidationRejectsNonsense() {
        ResilienceSettings s = new ResilienceSettings();
        assertThat(s.validate()).isNull();
        s.retries = -1;
        assertThat(s.validate()).contains("retries");
        s.retries = 0;
        s.adaptive.relaxSlopeRelPerSec = 0.5;
        s.adaptive.riseSlopeRelPerSec = 0.1;
        assertThat(s.validate()).contains("hysteresis");
        s.adaptive = new ResilienceSettings.AdaptiveSettings();
        s.failureThreshold = 1;
        s.adaptive.minThreshold = 2;
        assertThat(s.validate()).contains("minThreshold");
        assertThat(Duration.ofMillis(s.openWaitMs)).isEqualTo(Duration.ofSeconds(10));
    }
}
