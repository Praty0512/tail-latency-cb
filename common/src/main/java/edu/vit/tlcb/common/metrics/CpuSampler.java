package edu.vit.tlcb.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Samples this process's CPU load once per second and exposes it as Micrometer gauges.
 *
 * <p><b>Source.</b> OSHI is the primary source. On some Windows hosts the performance-counter registry is
 * damaged ("Unable to locate English counter names") and OSHI then reports zero CPU time for every process;
 * the sampler detects that at start-up (a JVM that has demonstrably run code cannot have used 0 ms of CPU)
 * and falls back to the JVM's own {@code getProcessCpuTime()}. The source in use is reported by
 * {@link #source()} and recorded in every run export so the provenance of the numbers is never ambiguous.
 * On Linux (including the Docker images) OSHI reads {@code /proc} and works as intended.
 *
 * <p><b>Unit.</b> {@code cpu_pct} is the process CPU time divided by wall-clock time, times 100, i.e.
 * 100 means "one full core busy" and a multi-threaded process can exceed 100. {@code
 * tlcb.cpu.process.host_fraction} additionally reports the load as a fraction of all logical cores.
 */
public class CpuSampler {

    private static final Logger log = LoggerFactory.getLogger(CpuSampler.class);

    private final MeterRegistry registry;
    private final OperatingSystem os;
    private final int pid;
    private final int logicalCores;
    private final com.sun.management.OperatingSystemMXBean jmx;
    private ScheduledExecutorService executor;

    /** {@code auto}: validate OSHI against the JVM counter for the first samples; {@code oshi}/{@code jmx}: force. */
    private final String requestedSource;
    private volatile boolean useOshi = true;
    private boolean validating;
    private int validationSamples;
    private OSProcess previousOshi;
    private long previousJmxCpuNanos;
    private long previousWallNanos;

    private volatile double latestPct = Double.NaN;
    private double sumSinceDrain;
    private int samplesSinceDrain;
    private volatile double cpuSecondsTotal;

    public CpuSampler(MeterRegistry registry, String requestedSource) {
        this.registry = registry;
        this.requestedSource = requestedSource == null ? "auto" : requestedSource.toLowerCase();
        SystemInfo si = new SystemInfo();
        this.os = si.getOperatingSystem();
        this.pid = os.getProcessId();
        this.logicalCores = Math.max(1, si.getHardware().getProcessor().getLogicalProcessorCount());
        this.jmx = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    }

    @PostConstruct
    public void start() {
        previousOshi = os.getProcess(pid);
        switch (requestedSource) {
            case "jmx" -> useOshi = false;
            case "oshi" -> useOshi = true;
            default -> {
                // auto: use JMX values while OSHI is validated against it (see sample()).
                useOshi = false;
                validating = true;
            }
        }
        previousJmxCpuNanos = jmx.getProcessCpuTime();
        previousWallNanos = System.nanoTime();

        Gauge.builder("tlcb.cpu.process.pct", this, s -> s.latestPct)
                .description("Process CPU time / wall time x 100 (100 = one core), sampled every second")
                .register(registry);
        Gauge.builder("tlcb.cpu.process.host_fraction", this, s -> s.latestPct / 100.0 / s.logicalCores)
                .description("Process CPU load as a fraction of all logical cores")
                .register(registry);
        Gauge.builder("tlcb.cpu.process.seconds", this, s -> s.cpuSecondsTotal)
                .description("Cumulative user+kernel CPU seconds of this process")
                .register(registry);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cpu-sampler");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::sample, 1, 1, TimeUnit.SECONDS);
        log.info("CPU sampler started (source={}, logicalCores={})", source(), logicalCores);
    }

    @PreDestroy
    public void stop() {
        if (executor != null) executor.shutdownNow();
    }

    /** {@code oshi}, or {@code jmx} when OSHI was not usable or not requested. */
    public String source() {
        return useOshi ? "oshi" : "jmx";
    }

    /**
     * Auto mode: after a few samples compare OSHI's cumulative CPU seconds with the JVM's own counter. If
     * they agree (within 25 % + 0.5 s) OSHI is trusted from then on, otherwise the JVM counter is used.
     * Comparing cumulative CPU time (several seconds by the time the JVM has started Spring) discriminates
     * far better than comparing the load of an idle process.
     */
    private void validateOshi() {
        OSProcess current = os.getProcess(pid);
        double oshiSeconds = current == null ? 0 : (current.getKernelTime() + current.getUserTime()) / 1000.0;
        double jmxSeconds = jmx.getProcessCpuTime() / 1e9;
        boolean agree = Math.abs(oshiSeconds - jmxSeconds) <= 0.25 * jmxSeconds + 0.5;
        useOshi = agree;
        if (agree) {
            previousOshi = current;
        }
        validating = false;
        if (agree) {
            log.info("OSHI CPU time ({}s) agrees with the JVM counter ({}s): using OSHI", oshiSeconds, jmxSeconds);
        } else {
            log.warn("OSHI CPU time ({}s) disagrees with the JVM counter ({}s) (broken OS performance counters?): "
                    + "using the JVM's OperatingSystemMXBean for cpu_pct", oshiSeconds, jmxSeconds);
        }
    }

    private void sample() {
        try {
            if (validating && ++validationSamples >= 3) {
                // Switch only after the JMX baseline for this tick has been consumed below.
                try {
                    sampleOnce();
                } finally {
                    validateOshi();
                }
                return;
            }
            sampleOnce();
        } catch (Exception e) {
            log.warn("CPU sample failed: {}", e.toString());
        }
    }

    private void sampleOnce() {
        double pct;
        double cpuSeconds;
        if (useOshi) {
            OSProcess current = os.getProcess(pid);
            if (current == null || previousOshi == null) return;
            pct = current.getProcessCpuLoadBetweenTicks(previousOshi) * 100.0;
            previousOshi = current;
            cpuSeconds = (current.getKernelTime() + current.getUserTime()) / 1000.0;
        } else {
            long cpuNanos = jmx.getProcessCpuTime();
            long wallNanos = System.nanoTime();
            long dWall = wallNanos - previousWallNanos;
            pct = dWall <= 0 ? Double.NaN : (cpuNanos - previousJmxCpuNanos) * 100.0 / dWall;
            previousJmxCpuNanos = cpuNanos;
            previousWallNanos = wallNanos;
            cpuSeconds = cpuNanos / 1e9;
        }
        if (Double.isNaN(pct) || pct < 0) return;
        latestPct = pct;
        cpuSecondsTotal = cpuSeconds;
        synchronized (this) {
            sumSinceDrain += pct;
            samplesSinceDrain++;
        }
    }

    /** Most recent one-second sample (NaN before the first sample). */
    public double latestPct() {
        return latestPct;
    }

    /** Mean of the one-second samples since the previous call; NaN if none. Resets the accumulator. */
    public synchronized double drainAveragePct() {
        double avg = samplesSinceDrain == 0 ? Double.NaN : sumSinceDrain / samplesSinceDrain;
        sumSinceDrain = 0;
        samplesSinceDrain = 0;
        return avg;
    }

    /** Cumulative user+kernel CPU seconds consumed by this process (as of the last sample). */
    public double cpuSecondsTotal() {
        return cpuSecondsTotal;
    }

    public int logicalCores() {
        return logicalCores;
    }
}
