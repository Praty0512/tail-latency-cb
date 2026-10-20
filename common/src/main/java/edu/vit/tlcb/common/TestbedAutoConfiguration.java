package edu.vit.tlcb.common;

import edu.vit.tlcb.common.fault.FaultAdminController;
import edu.vit.tlcb.common.fault.FaultEngine;
import edu.vit.tlcb.common.fault.FaultInjectionFilter;
import edu.vit.tlcb.common.metrics.CbStateProvider;
import edu.vit.tlcb.common.metrics.CpuSampler;
import edu.vit.tlcb.common.metrics.InboundMetricsFilter;
import edu.vit.tlcb.common.metrics.LatencyMetrics;
import edu.vit.tlcb.common.metrics.RunControlController;
import edu.vit.tlcb.common.metrics.RunExportContributor;
import edu.vit.tlcb.common.metrics.RunMetricsCollector;
import edu.vit.tlcb.common.metrics.StateTransitionLog;
import edu.vit.tlcb.common.web.HealthController;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Wires the shared testbed infrastructure into every service that depends on {@code common}
 * (registered through {@code META-INF/spring/...AutoConfiguration.imports}): health endpoint, fault
 * injection, latency instrumentation, CPU sampling and the run-export admin API.
 */
@AutoConfiguration(after = {MetricsAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class,
        PrometheusMetricsExportAutoConfiguration.class})
@Import({HealthController.class, FaultAdminController.class, RunControlController.class})
public class TestbedAutoConfiguration {

    /** Outermost custom filter: measures the full inbound latency, including injected delay. */
    public static final int METRICS_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    /** Runs inside the metrics filter so injected delay is part of the measured latency. */
    public static final int FAULT_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    // ---------------------------------------------------------------- fault injection

    @Bean
    public FaultEngine faultEngine() {
        return new FaultEngine();
    }

    @Bean
    public FilterRegistrationBean<FaultInjectionFilter> faultInjectionFilter(FaultEngine engine) {
        FilterRegistrationBean<FaultInjectionFilter> reg = new FilterRegistrationBean<>(new FaultInjectionFilter(engine));
        reg.addUrlPatterns("/*");
        reg.setOrder(FAULT_FILTER_ORDER);
        reg.setName("faultInjectionFilter");
        return reg;
    }

    // ---------------------------------------------------------------- instrumentation

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> serviceTag(@Value("${spring.application.name}") String service) {
        return registry -> registry.config().commonTags("service", service);
    }

    /**
     * Percentile histograms for Spring's built-in HTTP timers. They reach Prometheus as histogram buckets
     * (use histogram_quantile()); ready-made p50/p95/p99 values are published by the tlcb.latency.quantile gauges.
     */
    @Bean
    public MeterFilter httpPercentileFilter() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (id.getName().startsWith("http.server.requests") || id.getName().startsWith("http.client.requests")) {
                    return DistributionStatisticConfig.builder()
                            .percentilesHistogram(true)
                            .percentiles(0.5, 0.95, 0.99)
                            .build()
                            .merge(config);
                }
                return config;
            }
        };
    }

    @Bean
    public LatencyMetrics latencyMetrics(MeterRegistry registry) {
        return new LatencyMetrics(registry);
    }

    @Bean
    public CpuSampler cpuSampler(MeterRegistry registry, @Value("${metrics.cpu.source:auto}") String source) {
        return new CpuSampler(registry, source);
    }

    @Bean
    public StateTransitionLog stateTransitionLog() {
        return new StateTransitionLog();
    }

    @Bean
    public FilterRegistrationBean<InboundMetricsFilter> inboundMetricsFilter(LatencyMetrics metrics) {
        FilterRegistrationBean<InboundMetricsFilter> reg = new FilterRegistrationBean<>(new InboundMetricsFilter(metrics));
        reg.addUrlPatterns("/api/*");
        reg.setOrder(METRICS_FILTER_ORDER);
        reg.setName("inboundMetricsFilter");
        return reg;
    }

    @Bean
    public RunMetricsCollector runMetricsCollector(
            @Value("${spring.application.name}") String service,
            LatencyMetrics metrics, CpuSampler cpu,
            ObjectProvider<CbStateProvider> cbStates,
            List<RunExportContributor> contributors,
            @Value("${metrics.export.interval-ms:5000}") long intervalMs,
            @Value("${metrics.export.dir:}") String exportDir) {
        return new RunMetricsCollector(service, metrics, cpu, cbStates.getIfAvailable(), contributors, intervalMs, exportDir);
    }
}
