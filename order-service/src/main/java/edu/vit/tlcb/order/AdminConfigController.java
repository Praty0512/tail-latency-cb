package edu.vit.tlcb.order;

import edu.vit.tlcb.resilience.ResilienceSettings;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime selection of the system under test: {@code POST /admin/config} installs a
 * {@link ResilienceSettings} document (system none|static|adaptive, timeout, retries, threshold, ...),
 * {@code GET /admin/config} returns the active settings together with the live breaker state.
 *
 * <p>Example: {@code {"configId":"mixed__static__t2000_r3_f5","system":"static","timeoutMs":2000,
 * "retries":3,"failureThreshold":5}}. The admin API is unauthenticated by design (testbed only).
 */
@RestController
public class AdminConfigController {

    private final ResilienceManager manager;

    public AdminConfigController(ResilienceManager manager) {
        this.manager = manager;
    }

    @PostMapping("/admin/config")
    public ResponseEntity<Map<String, Object>> apply(@RequestBody ResilienceSettings settings) {
        String err = manager.apply(settings);
        if (err != null) {
            return ResponseEntity.badRequest().body(Map.of("error", err));
        }
        return ResponseEntity.ok(state());
    }

    @GetMapping("/admin/config")
    public Map<String, Object> get() {
        return state();
    }

    private Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settings", manager.current());
        m.put("breakers", manager.statuses());
        return m;
    }
}
