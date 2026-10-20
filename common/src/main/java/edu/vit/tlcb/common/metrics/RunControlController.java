package edu.vit.tlcb.common.metrics;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin API used by the experiment harness (and by hand) to delimit a measured run and to fetch its
 * metrics: {@code POST /admin/run/start}, {@code POST /admin/run/stop}, {@code GET /admin/run/status},
 * {@code GET /admin/run/export?format=json|csv}.
 */
@RestController
public class RunControlController {

    private final RunMetricsCollector collector;

    public RunControlController(RunMetricsCollector collector) {
        this.collector = collector;
    }

    @PostMapping("/admin/run/start")
    public Map<String, Object> start(@RequestBody RunMetricsCollector.StartRequest req) {
        return collector.start(req);
    }

    @PostMapping("/admin/run/stop")
    public Map<String, Object> stop() {
        return collector.stop();
    }

    @GetMapping("/admin/run/status")
    public Map<String, Object> status() {
        return collector.status();
    }

    @GetMapping("/admin/run/export")
    public ResponseEntity<?> export(@RequestParam(defaultValue = "json") String format) {
        if (!collector.hasRun()) {
            return ResponseEntity.status(409).body(Map.of("error", "no run has been started"));
        }
        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok().contentType(new MediaType("text", "csv")).body(collector.exportCsv());
        }
        return ResponseEntity.ok(collector.exportJson());
    }
}
