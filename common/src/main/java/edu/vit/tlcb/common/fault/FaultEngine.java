package edu.vit.tlcb.common.fault;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides, for each request, whether a fault is injected. Pure decision logic (no servlet types) so it
 * can be unit-tested; the servlet filter and the admin endpoint delegate to it.
 *
 * <p>The active spec is swapped atomically, so it can be changed while traffic is flowing.
 */
public class FaultEngine {

    /** The outcome of evaluating the rules for one request. */
    public record Decision(long delayMs, Integer errorStatus, List<String> firedRules) {
        public static final Decision NONE = new Decision(0, null, List.of());

        public boolean isNone() {
            return delayMs == 0 && errorStatus == null;
        }
    }

    private record Active(FaultSpec spec, long originMs) {
    }

    private volatile Active active = new Active(new FaultSpec(false, 0L, null, List.of()), 0L);
    private final AtomicLong arrivalCounter = new AtomicLong();
    private final AtomicLong requestsEvaluated = new AtomicLong();
    private final AtomicLong delaysInjected = new AtomicLong();
    private final AtomicLong errorsInjected = new AtomicLong();
    private final AtomicLong totalDelayMs = new AtomicLong();

    /** Replaces the active fault specification. Counters are reset. */
    public void apply(FaultSpec spec, long nowMs) {
        long origin = spec.originEpochMs() != null ? spec.originEpochMs() : nowMs;
        resetCounters();
        this.active = new Active(spec, origin);
    }

    public void clear() {
        resetCounters();
        this.active = new Active(new FaultSpec(false, 0L, null, List.of()), 0L);
    }

    private void resetCounters() {
        requestsEvaluated.set(0);
        delaysInjected.set(0);
        errorsInjected.set(0);
        totalDelayMs.set(0);
    }

    public FaultSpec currentSpec() {
        return active.spec();
    }

    public long currentOriginMs() {
        return active.originMs();
    }

    /**
     * Evaluates the rules for one request.
     *
     * @param path       request path (rules only match their {@code pathPrefix})
     * @param requestKey stable key supplied by the caller ({@code X-Request-Key}); if null the arrival
     *                   order is used (deterministic only for sequential traffic)
     * @param attempt    retry attempt number (0 = first try) so retries get independent draws
     * @param nowMs      current wall-clock time in epoch millis
     */
    public Decision decide(String path, String requestKey, int attempt, long nowMs) {
        Active a = active;
        if (!a.spec().enabledOrDefault()) {
            return Decision.NONE;
        }
        requestsEvaluated.incrementAndGet();
        long key = requestKey != null && !requestKey.isEmpty()
                ? DeterministicRandom.hash(requestKey)
                : DeterministicRandom.mix(arrivalCounter.incrementAndGet());
        long seed = a.spec().seedOrDefault();
        long elapsed = nowMs - a.originMs();

        long delay = 0;
        Integer errorStatus = null;
        List<String> fired = null;
        List<FaultRule> rules = a.spec().rulesOrEmpty();
        for (int i = 0; i < rules.size(); i++) {
            FaultRule r = rules.get(i);
            if (!path.startsWith(r.pathPrefixOrDefault())) continue;
            if (r.startOffsetMs() != null && elapsed < r.startOffsetMs()) continue;
            if (r.endOffsetMs() != null && elapsed >= r.endOffsetMs()) continue;
            if (DeterministicRandom.uniform(seed, key, attempt, i, 0) >= r.probabilityOrDefault()) continue;

            switch (r.type()) {
                case DELAY -> delay += r.delayMs();
                case RANDOM_DELAY -> delay += sampleDelay(r, seed, key, attempt, i);
                case ERROR -> {
                    if (errorStatus == null) errorStatus = r.statusOrDefault();
                }
            }
            if (fired == null) fired = new ArrayList<>(2);
            fired.add(r.id() == null ? "rule" + i : r.id());
        }
        if (fired == null) {
            return Decision.NONE;
        }
        if (delay > 0) {
            delaysInjected.incrementAndGet();
            totalDelayMs.addAndGet(delay);
        }
        if (errorStatus != null) {
            errorsInjected.incrementAndGet();
        }
        return new Decision(delay, errorStatus, List.copyOf(fired));
    }

    static long sampleDelay(FaultRule r, long seed, long key, int attempt, int ruleIndex) {
        double u1 = DeterministicRandom.uniform(seed, key, attempt, ruleIndex, 1);
        double ms;
        switch (r.distribution()) {
            case UNIFORM -> ms = r.minMs() + u1 * (r.maxMs() - r.minMs());
            case EXPONENTIAL -> ms = -r.meanMs() * Math.log(1.0 - u1);
            case LOGNORMAL -> {
                double u2 = DeterministicRandom.uniform(seed, key, attempt, ruleIndex, 2);
                double z = Math.sqrt(-2.0 * Math.log(1.0 - u1)) * Math.cos(2.0 * Math.PI * u2);
                ms = r.medianMs() * Math.exp(r.sigma() * z);
            }
            default -> ms = 0;
        }
        return Math.min(r.maxDelayOrDefault(), Math.max(0L, Math.round(ms)));
    }

    public record Stats(long requestsEvaluated, long delaysInjected, long errorsInjected, long totalDelayMs) {
    }

    public Stats stats() {
        return new Stats(requestsEvaluated.get(), delaysInjected.get(), errorsInjected.get(), totalDelayMs.get());
    }
}
