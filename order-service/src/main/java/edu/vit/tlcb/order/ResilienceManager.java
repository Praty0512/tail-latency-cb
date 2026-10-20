package edu.vit.tlcb.order;

import edu.vit.tlcb.common.metrics.CbStateProvider;
import edu.vit.tlcb.common.metrics.LatencyMetrics;
import edu.vit.tlcb.common.metrics.RunExportContributor;
import edu.vit.tlcb.common.metrics.StateTransitionLog;
import edu.vit.tlcb.resilience.AdaptiveThresholdController;
import edu.vit.tlcb.resilience.Breaker;
import edu.vit.tlcb.resilience.BreakerFactory;
import edu.vit.tlcb.resilience.BreakerState;
import edu.vit.tlcb.resilience.ResilienceSettings;
import edu.vit.tlcb.resilience.ResilientCall;
import edu.vit.tlcb.resilience.TailLatencyAdaptiveBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Switches the order service between the three systems under test ("none", "static", "adaptive") and between
 * grid cells of the sweep, <em>at runtime</em>, without a restart: {@link #apply} builds a fresh breaker, retry
 * and time limiter per downstream target and installs them in the {@link OutboundCallRegistry}.
 *
 * <p>It also exposes the live breaker state to the metrics export ({@link CbStateProvider}), the Prometheus
 * gauges {@code tlcb_cb_state} / {@code tlcb_cb_threshold}, and the adaptation audit log
 * ({@link RunExportContributor}).
 */
@Component
public class ResilienceManager implements CbStateProvider, RunExportContributor {

    private static final Logger log = LoggerFactory.getLogger(ResilienceManager.class);
    private static final List<String> TARGETS = List.of("inventory-service", "payment-service");

    private final OutboundCallRegistry registry;
    private final LatencyMetrics metrics;
    private final StateTransitionLog transitions;
    private final MeterRegistry meters;
    private final ScheduledThreadPoolExecutor scheduler;
    private final ResilienceSettings defaults = new ResilienceSettings();

    private volatile ResilienceSettings current;
    private volatile Map<String, Breaker> breakers = Map.of();

    public ResilienceManager(OutboundCallRegistry registry, LatencyMetrics metrics, StateTransitionLog transitions,
                             MeterRegistry meters,
                             @Value("${resilience.system:none}") String system,
                             @Value("${resilience.timeout-ms:2000}") long timeoutMs,
                             @Value("${resilience.retries:0}") int retries,
                             @Value("${resilience.failure-threshold:5}") int threshold) {
        this.registry = registry;
        this.metrics = metrics;
        this.transitions = transitions;
        this.meters = meters;
        this.scheduler = new ScheduledThreadPoolExecutor(4, r -> {
            Thread t = new Thread(r, "resilience-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.setRemoveOnCancelPolicy(true); // timeout tasks are cancelled on every normal completion
        defaults.system = ResilienceSettings.SystemType.valueOf(system.toUpperCase());
        defaults.timeoutMs = timeoutMs;
        defaults.retries = retries;
        defaults.failureThreshold = threshold;
        defaults.configId = "startup-" + system;
    }

    @PostConstruct
    void init() {
        for (String target : TARGETS) {
            Gauge.builder("tlcb.cb.state", this, m -> m.stateCode(target))
                    .description("Circuit breaker state: 0=CLOSED 1=OPEN 2=HALF_OPEN -1=none")
                    .tag("target", target).register(meters);
            Gauge.builder("tlcb.cb.threshold", this, m -> m.thresholdOf(target))
                    .description("Consecutive failures that currently open the breaker (-1 = no breaker)")
                    .tag("target", target).register(meters);
        }
        apply(defaults);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    public ResilienceSettings current() {
        return current;
    }

    /** Installs a new resilience configuration. Returns an error message if it is invalid, else null. */
    public synchronized String apply(ResilienceSettings settings) {
        if (settings.adaptive == null) settings.adaptive = new ResilienceSettings.AdaptiveSettings();
        String err = settings.validate();
        if (err != null) return err;

        Map<String, Breaker> old = breakers;
        Map<String, Breaker> fresh = new LinkedHashMap<>();
        Map<String, OutboundCall> calls = new LinkedHashMap<>();
        for (String target : TARGETS) {
            Breaker b = BreakerFactory.create(target, settings, scheduler);
            b.addListener(t -> transitions.record(t.breaker(), t.from().name(), t.to().name(), t.reason()));
            fresh.put(target, b);
            metrics.declare(LatencyMetrics.ATTEMPT, target);
            DownstreamClient client = target.startsWith("inventory") ? registry.inventoryClient() : registry.paymentClient();
            ResilientCall rc = new ResilientCall(target, settings, b, scheduler,
                    (nanos, failure) -> metrics.attempt(target, nanos, failure));
            long safety = (settings.retries + 1L) * (settings.timeoutMs + settings.retryWaitMs) + 5_000;
            calls.put(target, new ResilientOutboundCall(client, rc, safety));
        }
        this.breakers = fresh;
        this.current = settings;
        registry.install(calls.get("inventory-service"), calls.get("payment-service"));
        old.values().forEach(Breaker::close);
        log.info("RESILIENCE-CONFIG configId={} system={} timeoutMs={} retries={} threshold={} openWaitMs={}",
                settings.configId, settings.system, settings.timeoutMs, settings.retries,
                settings.failureThreshold, settings.openWaitMs);
        return null;
    }

    // ------------------------------------------------------------------ live state

    private double stateCode(String target) {
        Breaker b = breakers.get(target);
        if (b == null) return -1;
        return switch (b.state()) {
            case CLOSED -> 0;
            case OPEN -> 1;
            case HALF_OPEN -> 2;
            default -> -1;
        };
    }

    private double thresholdOf(String target) {
        Breaker b = breakers.get(target);
        return b == null ? -1 : b.failureThreshold();
    }

    @Override
    public Map<String, Status> statuses() {
        Map<String, Status> out = new LinkedHashMap<>();
        breakers.forEach((target, b) -> {
            BreakerState s = b.state();
            out.put(target, s == BreakerState.NONE ? Status.NONE : new Status(s.name(), b.failureThreshold()));
        });
        return out;
    }

    // ------------------------------------------------------------------ export of the adaptation audit log

    @Override
    public String key() {
        return "resilience";
    }

    @Override
    public void reset(long originEpochMs) {
        for (Breaker b : breakers.values()) {
            if (b instanceof TailLatencyAdaptiveBreaker a) {
                a.controller().clearHistory(originEpochMs);
            }
        }
    }

    @Override
    public Object snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", current);
        Map<String, Object> targets = new LinkedHashMap<>();
        breakers.forEach((target, b) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("finalState", b.state().name());
            m.put("finalThreshold", b.failureThreshold());
            if (b instanceof TailLatencyAdaptiveBreaker a) {
                AdaptiveThresholdController c = a.controller();
                m.put("baselineThreshold", c.baseline());
                m.put("adaptationEvents", c.events().stream().map(ResilienceManager::eventMap).toList());
                m.put("evaluationTrace", c.trace().stream().map(ResilienceManager::traceMap).toList());
            }
            targets.put(target, m);
        });
        out.put("targets", targets);
        return out;
    }

    private static Double nn(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    private static Map<String, Object> eventMap(edu.vit.tlcb.resilience.AdaptationEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("epochMs", e.epochMs());
        m.put("tSec", e.tSec());
        m.put("action", e.action().name());
        m.put("oldThreshold", e.oldThreshold());
        m.put("newThreshold", e.newThreshold());
        m.put("reason", e.reason());
        m.put("p95Ms", nn(e.p95Ms()));
        m.put("p99Ms", nn(e.p99Ms()));
        m.put("p99Ewma", nn(e.p99Ewma()));
        m.put("slopeMsPerSec", nn(e.slopeMsPerSec()));
        m.put("relativeSlopePerSec", nn(e.relativeSlopePerSec()));
        m.put("windowSamples", e.windowSamples());
        return m;
    }

    private static Map<String, Object> traceMap(AdaptiveThresholdController.TracePoint p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("epochMs", p.epochMs());
        m.put("tSec", p.tSec());
        m.put("threshold", p.threshold());
        m.put("p95Ms", nn(p.p95Ms()));
        m.put("p99Ms", nn(p.p99Ms()));
        m.put("p99Ewma", nn(p.p99Ewma()));
        m.put("slopeMsPerSec", nn(p.slopeMsPerSec()));
        m.put("relativeSlopePerSec", nn(p.relativeSlopePerSec()));
        m.put("samples", p.samples());
        m.put("action", p.action());
        return m;
    }
}
