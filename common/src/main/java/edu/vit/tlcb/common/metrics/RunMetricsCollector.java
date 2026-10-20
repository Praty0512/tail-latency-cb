package edu.vit.tlcb.common.metrics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns the lifecycle of one experiment run on one service: {@code start} resets all per-run state and
 * begins ticking; every tick turns each latency series into a {@link MetricRow}; {@code stop} flushes the
 * last interval and freezes a run summary. The result is available as CSV and as JSON, and is also written
 * to disk if {@code metrics.export.dir} is configured.
 */
public class RunMetricsCollector {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StartRequest(String configId, String scenario, String system, Integer run, Long intervalMs) {
    }

    private static final Logger log = LoggerFactory.getLogger(RunMetricsCollector.class);

    private final String service;
    private final LatencyMetrics metrics;
    private final CpuSampler cpu;
    private final CbStateProvider cbStates;
    private final List<RunExportContributor> contributors;
    private final long defaultIntervalMs;
    private final Path exportDir;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "metrics-export");
        t.setDaemon(true);
        return t;
    });

    // ---- per-run state (guarded by 'this')
    private StartRequest request;
    private long startEpochMs;
    private long endEpochMs;
    private long intervalMs;
    private boolean running;
    private double cpuSecondsAtStart;
    private double cpuSecondsAtStop;
    private double cpuPctSum;
    private int cpuPctSamples;
    private final List<MetricRow> rows = new ArrayList<>();
    private List<Map<String, Object>> summary = List.of();
    private ScheduledFuture<?> ticker;

    public RunMetricsCollector(String service, LatencyMetrics metrics, CpuSampler cpu, CbStateProvider cbStates,
                               List<RunExportContributor> contributors, long defaultIntervalMs, String exportDir) {
        this.service = service;
        this.metrics = metrics;
        this.cpu = cpu;
        this.cbStates = cbStates;
        this.contributors = contributors;
        this.defaultIntervalMs = defaultIntervalMs;
        this.exportDir = exportDir == null || exportDir.isBlank() ? null : Path.of(exportDir);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    public synchronized Map<String, Object> start(StartRequest req) {
        if (running) {
            stopInternal();
        }
        this.request = req;
        this.intervalMs = req.intervalMs() != null && req.intervalMs() >= 200 ? req.intervalMs() : defaultIntervalMs;
        metrics.resetRunState();
        rows.clear();
        summary = List.of();
        cpu.drainAveragePct();
        cpuPctSum = 0;
        cpuPctSamples = 0;
        cpuSecondsAtStart = cpu.cpuSecondsTotal();
        startEpochMs = System.currentTimeMillis();
        endEpochMs = 0;
        for (RunExportContributor c : contributors) {
            c.reset(startEpochMs);
        }
        running = true;
        ticker = scheduler.scheduleAtFixedRate(this::tickSafely, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("RUN-START service={} configId={} scenario={} system={} run={} intervalMs={}",
                service, req.configId(), req.scenario(), req.system(), req.run(), intervalMs);
        return status();
    }

    public synchronized Map<String, Object> stop() {
        if (running) {
            stopInternal();
        }
        return status();
    }

    private void stopInternal() {
        if (ticker != null) ticker.cancel(false);
        tick(); // flush the open interval
        endEpochMs = System.currentTimeMillis();
        cpuSecondsAtStop = cpu.cpuSecondsTotal();
        running = false;

        List<Map<String, Object>> s = new ArrayList<>();
        for (LatencySeries series : sortedSeries()) {
            LatencySeries.Stats st = series.cumulativeStats();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("service", service);
            m.put("direction", series.direction());
            m.put("endpoint", series.endpoint());
            m.put("count", st.count());
            m.put("errors", st.errors());
            m.put("rejected", st.rejected());
            m.put("errorRate", MetricRow.nn(st.errorRate()));
            m.put("avg", MetricRow.nn(st.avg()));
            m.put("p50", MetricRow.nn(st.p50()));
            m.put("p95", MetricRow.nn(st.p95()));
            m.put("p99", MetricRow.nn(st.p99()));
            m.put("p999", MetricRow.nn(st.p999()));
            m.put("max", MetricRow.nn(st.max()));
            s.add(m);
        }
        summary = s;
        log.info("RUN-STOP service={} configId={} rows={}", service, request == null ? null : request.configId(), rows.size());
        writeFiles();
    }

    private void tickSafely() {
        try {
            synchronized (this) {
                if (running) tick();
            }
        } catch (Exception e) {
            log.warn("metrics tick failed", e);
        }
    }

    private List<LatencySeries> sortedSeries() {
        List<LatencySeries> all = metrics.all();
        all.sort(Comparator.comparing(LatencySeries::direction).thenComparing(LatencySeries::endpoint));
        return all;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        String ts = Instant.ofEpochMilli(now).toString();
        double tSec = (now - startEpochMs) / 1000.0;
        double cpuPct = cpu.drainAveragePct();
        if (!Double.isNaN(cpuPct)) {
            cpuPctSum += cpuPct;
            cpuPctSamples++;
        }
        Double cpuBoxed = MetricRow.nn(cpuPct);
        Map<String, CbStateProvider.Status> states = cbStates == null ? Map.of() : cbStates.statuses();
        String configId = request == null ? "" : request.configId();

        // One row per process so CPU is always present, even for an idle or restarting service.
        rows.add(new MetricRow(ts, service, null, null, null, null, null, cpuBoxed, "NA", configId,
                "process", "-", 0, 0, 0, tSec, -1));

        for (LatencySeries s : sortedSeries()) {
            LatencySeries.Stats st = s.drain();
            CbStateProvider.Status cb = !LatencyMetrics.INBOUND.equals(s.direction())
                    ? states.getOrDefault(s.endpoint(), CbStateProvider.Status.NONE)
                    : CbStateProvider.Status.NONE;
            rows.add(new MetricRow(ts, service,
                    MetricRow.nn(st.p50()), MetricRow.nn(st.p95()), MetricRow.nn(st.p99()), MetricRow.nn(st.avg()),
                    MetricRow.nn(st.errorRate()), cpuBoxed, cb.state(), configId,
                    s.direction(), s.endpoint(), st.count(), st.errors(), st.rejected(), tSec, cb.threshold()));
        }
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("service", service);
        m.put("running", running);
        m.put("configId", request == null ? null : request.configId());
        m.put("scenario", request == null ? null : request.scenario());
        m.put("system", request == null ? null : request.system());
        m.put("run", request == null ? null : request.run());
        m.put("rows", rows.size());
        return m;
    }

    public synchronized boolean hasRun() {
        return request != null;
    }

    /** Full JSON export: meta, interval rows, run summary and every contributor's section. */
    public synchronized Map<String, Object> exportJson() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("service", service);
        meta.put("configId", request == null ? null : request.configId());
        meta.put("scenario", request == null ? null : request.scenario());
        meta.put("system", request == null ? null : request.system());
        meta.put("run", request == null ? null : request.run());
        meta.put("startEpochMs", startEpochMs);
        meta.put("endEpochMs", endEpochMs);
        meta.put("intervalMs", intervalMs);
        meta.put("running", running);
        meta.put("logicalCores", cpu.logicalCores());
        meta.put("cpuSource", cpu.source());
        meta.put("javaVersion", System.getProperty("java.version"));
        double endCpu = running ? cpu.cpuSecondsTotal() : cpuSecondsAtStop;
        meta.put("cpuSeconds", Math.max(0.0, endCpu - cpuSecondsAtStart));
        meta.put("cpuPctMean", cpuPctSamples == 0 ? null : cpuPctSum / cpuPctSamples);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meta", meta);
        out.put("summary", summary);
        out.put("intervals", List.copyOf(rows));
        for (RunExportContributor c : contributors) {
            out.put(c.key(), c.snapshot());
        }
        return out;
    }

    public synchronized String exportCsv() {
        StringBuilder sb = new StringBuilder(MetricRow.CSV_HEADER).append('\n');
        for (MetricRow r : rows) sb.append(r.toCsvLine()).append('\n');
        return sb.toString();
    }

    private void writeFiles() {
        if (exportDir == null || request == null) return;
        try {
            Path dir = exportDir.resolve(request.configId() == null ? "unnamed" : request.configId());
            Files.createDirectories(dir);
            String base = service + "_run" + (request.run() == null ? 0 : request.run());
            Files.writeString(dir.resolve(base + ".csv"), exportCsv(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(base + ".json"),
                    new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                            .writeValueAsString(exportJson()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("could not write export files: {}", e.toString());
        }
    }
}
