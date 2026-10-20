package edu.vit.tlcb.resilience;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConsecutiveFailureBreakerTest {

    private final FakeTime time = new FakeTime();

    private ConsecutiveFailureBreaker breaker(int threshold, long openWaitMs, int probes) {
        return new ConsecutiveFailureBreaker("b", () -> threshold, openWaitMs, probes, time::nanos, time::epochMs);
    }

    private static void fail(Breaker b, int n) {
        for (int i = 0; i < n; i++) {
            assertThat(b.tryAcquirePermission()).isTrue();
            b.onFailure(1, new RuntimeException("x"));
        }
    }

    @Test
    void opensExactlyAtNConsecutiveFailures() {
        ConsecutiveFailureBreaker b = breaker(5, 10_000, 3);
        fail(b, 4);
        assertThat(b.state()).isEqualTo(BreakerState.CLOSED);
        fail(b, 1);
        assertThat(b.state()).isEqualTo(BreakerState.OPEN);
        assertThat(b.tryAcquirePermission()).isFalse();
    }

    @Test
    void aSuccessResetsTheStreak() {
        ConsecutiveFailureBreaker b = breaker(3, 10_000, 3);
        fail(b, 2);
        b.tryAcquirePermission();
        b.onSuccess(1);
        fail(b, 2);
        assertThat(b.state()).isEqualTo(BreakerState.CLOSED);
        fail(b, 1);
        assertThat(b.state()).isEqualTo(BreakerState.OPEN);
    }

    @Test
    void staysOpenForTheWaitThenProbes() {
        ConsecutiveFailureBreaker b = breaker(2, 10_000, 3);
        fail(b, 2);
        time.advanceMs(9_999);
        assertThat(b.state()).isEqualTo(BreakerState.OPEN);
        time.advanceMs(1);
        assertThat(b.state()).isEqualTo(BreakerState.HALF_OPEN);
    }

    @Test
    void halfOpenAdmitsOnlyTheConfiguredNumberOfProbes() {
        ConsecutiveFailureBreaker b = breaker(2, 1_000, 3);
        fail(b, 2);
        time.advanceMs(1_000);
        assertThat(b.tryAcquirePermission()).isTrue();
        assertThat(b.tryAcquirePermission()).isTrue();
        assertThat(b.tryAcquirePermission()).isTrue();
        assertThat(b.tryAcquirePermission()).isFalse();
    }

    @Test
    void closesWhenAnyProbeSucceedsAndReopensWhenAllFail() {
        ConsecutiveFailureBreaker b = breaker(2, 1_000, 3);
        fail(b, 2);
        time.advanceMs(1_000);
        b.tryAcquirePermission();
        b.onFailure(1, new RuntimeException());
        b.tryAcquirePermission();
        b.onSuccess(1);
        b.tryAcquirePermission();
        b.onFailure(1, new RuntimeException());
        assertThat(b.state()).isEqualTo(BreakerState.CLOSED); // 1 of 3 succeeded

        fail(b, 2);
        assertThat(b.state()).isEqualTo(BreakerState.OPEN);
        time.advanceMs(1_000);
        for (int i = 0; i < 3; i++) {
            assertThat(b.tryAcquirePermission()).isTrue();
            b.onFailure(1, new RuntimeException());
        }
        assertThat(b.state()).isEqualTo(BreakerState.OPEN);
    }

    @Test
    void resultsOfCallsStartedBeforeOpeningAreIgnoredWhileOpen() {
        ConsecutiveFailureBreaker b = breaker(2, 10_000, 3);
        b.tryAcquirePermission(); // slow call A starts while closed
        fail(b, 2);               // two fast failures open the breaker
        b.onSuccess(1);           // A finally returns successfully: must not close the breaker
        assertThat(b.state()).isEqualTo(BreakerState.OPEN);
    }

    @Test
    void thresholdIsReadLiveAndAppliesToTheNextFailure() {
        AtomicInteger t = new AtomicInteger(5);
        ConsecutiveFailureBreaker b = new ConsecutiveFailureBreaker("b", t::get, 10_000, 3, time::nanos, time::epochMs);
        fail(b, 3);
        t.set(3);                                  // tightened mid-streak: 3 failures so far
        assertThat(b.state()).isEqualTo(BreakerState.CLOSED);
        fail(b, 1);                                // next failure makes streak 4 >= 3
        assertThat(b.state()).isEqualTo(BreakerState.OPEN);
    }

    @Test
    void transitionsAreReportedWithReasons() {
        ConsecutiveFailureBreaker b = breaker(2, 1_000, 1);
        List<StateTransition> seen = new ArrayList<>();
        b.addListener(seen::add);
        fail(b, 2);
        time.advanceMs(1_000);
        b.tryAcquirePermission();
        b.onSuccess(1);
        assertThat(seen).extracting(StateTransition::to)
                .containsExactly(BreakerState.OPEN, BreakerState.HALF_OPEN, BreakerState.CLOSED);
        assertThat(seen.get(0).reason()).contains("consecutive failures");
    }
}
