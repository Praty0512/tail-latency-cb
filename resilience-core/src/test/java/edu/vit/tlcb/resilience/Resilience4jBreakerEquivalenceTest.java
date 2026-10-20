package edu.vit.tlcb.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the two claims the static-vs-adaptive comparison rests on:
 * <ol>
 *   <li>a Resilience4j breaker configured as COUNT_BASED window N / 100 % failure rate behaves exactly like
 *       "open after N consecutive failures" (so no custom counter is needed for the static baseline);</li>
 *   <li>the custom {@link ConsecutiveFailureBreaker} with a constant threshold is state-for-state identical to
 *       that Resilience4j breaker, so any difference measured for the adaptive system comes from the
 *       adaptation, not from the implementation.</li>
 * </ol>
 * Both breakers receive the same random trace of calls and "time passes" events; after every step their
 * permission decisions and states must agree.
 */
class Resilience4jBreakerEquivalenceTest {

    private static final long WAIT_MS = 10_000;

    private void runTrace(int threshold, int probes, long seed, int steps) {
        FakeTime time = new FakeTime();
        // R4J: huge wait + lazy transition so that time only advances through the explicit TIME step below.
        Resilience4jBreaker r4j = new Resilience4jBreaker("r4j", threshold, Duration.ofHours(1), probes, false);
        CircuitBreaker raw = r4j.unwrap();
        ConsecutiveFailureBreaker custom = new ConsecutiveFailureBreaker("custom", () -> threshold, WAIT_MS, probes,
                time::nanos, time::epochMs);
        Random rnd = new Random(seed);
        StringBuilder ops = new StringBuilder();
        double failP = 0.2 + rnd.nextDouble() * 0.7; // each trace has its own failure intensity

        for (int step = 0; step < steps; step++) {
            double r = rnd.nextDouble();
            if (r < 0.04) {
                // time passes: the open wait elapses
                ops.append(" T");
                time.advanceMs(WAIT_MS);
                if (raw.getState() == CircuitBreaker.State.OPEN) raw.transitionToHalfOpenState();
            } else {
                boolean permittedR4j = r4j.tryAcquirePermission();
                boolean permittedCustom = custom.tryAcquirePermission();
                ops.append(permittedR4j ? " acq" : " rej");
                assertThat(permittedCustom).as("permission at step %d (N=%d, P=%d, seed=%d) ops:%s", step, threshold, probes, seed, ops)
                        .isEqualTo(permittedR4j);
                if (permittedR4j) {
                    if (rnd.nextDouble() < failP) {
                        ops.append("+F");
                        r4j.onFailure(1, new RuntimeException());
                        custom.onFailure(1, new RuntimeException());
                    } else {
                        ops.append("+S");
                        r4j.onSuccess(1);
                        custom.onSuccess(1);
                    }
                }
            }
            assertThat(custom.state()).as("state at step %d (N=%d, P=%d, seed=%d) ops:%s", step, threshold, probes, seed, ops)
                    .isEqualTo(r4j.state());
        }
    }

    @Test
    void customMachineMatchesResilience4jOnRandomTraces() {
        for (int threshold = 1; threshold <= 10; threshold++) {
            for (int probes = 1; probes <= Math.min(3, threshold); probes++) { // equivalence is claimed for N >= P
                for (long seed = 0; seed < 8; seed++) {
                    runTrace(threshold, probes, seed * 7919 + threshold * 31 + probes, 4000);
                }
            }
        }
    }

    /**
     * Documented difference: Resilience4j evaluates the HALF_OPEN outcome after {@code min(minimumNumberOfCalls,
     * probes)} calls, and the static baseline sets {@code minimumNumberOfCalls = N}. With N &lt; probes it therefore
     * decides on fewer probes than configured. This cannot occur in the study grid (N in {3,5,10}, 3 probes); the
     * custom machine always waits for all probes, so equivalence is only claimed (and tested) for N &gt;= probes.
     */
    @Test
    void resilience4jDecidesHalfOpenAfterMinOfNAndProbesCalls() {
        FakeTime time = new FakeTime();
        Resilience4jBreaker r4j = new Resilience4jBreaker("r4j", 1, Duration.ofHours(1), 2, false);
        ConsecutiveFailureBreaker custom = new ConsecutiveFailureBreaker("c", () -> 1, 10_000, 2, time::nanos, time::epochMs);
        for (Breaker b : new Breaker[]{r4j, custom}) {
            b.tryAcquirePermission();
            b.onFailure(1, new RuntimeException()); // N=1: opens
        }
        r4j.unwrap().transitionToHalfOpenState();
        time.advanceMs(10_000);
        for (Breaker b : new Breaker[]{r4j, custom}) {
            assertThat(b.tryAcquirePermission()).isTrue();
            b.onFailure(1, new RuntimeException()); // first of two probes fails
        }
        assertThat(r4j.state()).isEqualTo(BreakerState.OPEN);        // Resilience4j: decided after 1 call
        assertThat(custom.state()).isEqualTo(BreakerState.HALF_OPEN); // custom: still waiting for the 2nd probe
    }

    @Test
    void resilience4jWindowOfNWithFullFailureRateIsExactlyNConsecutiveFailures() {
        Resilience4jBreaker b = new Resilience4jBreaker("r4j", 5, Duration.ofHours(1), 3, false);
        // F F F F S F F F F  -> never 5 in a row, must stay closed
        for (char c : "FFFFSFFFF".toCharArray()) {
            assertThat(b.tryAcquirePermission()).isTrue();
            if (c == 'F') b.onFailure(1, new RuntimeException()); else b.onSuccess(1);
        }
        assertThat(b.state()).isEqualTo(BreakerState.CLOSED);
        // the 5th consecutive failure (F x5 counted from the last four + one more) opens it
        b.tryAcquirePermission();
        b.onFailure(1, new RuntimeException());
        assertThat(b.state()).isEqualTo(BreakerState.OPEN);
    }
}
