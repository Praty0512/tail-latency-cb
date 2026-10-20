package edu.vit.tlcb.common.fault;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime control of fault injection: {@code POST /admin/fault} installs a spec,
 * {@code GET /admin/fault} reports the active spec and injection counters,
 * {@code DELETE /admin/fault} removes all faults.
 *
 * <p>The admin API is unauthenticated by design (testbed only); never expose it publicly.
 */
@RestController
public class FaultAdminController {

    private final FaultEngine engine;

    public FaultAdminController(FaultEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/admin/fault")
    public ResponseEntity<Map<String, Object>> apply(@RequestBody FaultSpec spec) {
        String err = spec.validate();
        if (err != null) {
            return ResponseEntity.badRequest().body(Map.of("error", err));
        }
        engine.apply(spec, System.currentTimeMillis());
        return ResponseEntity.ok(state());
    }

    @GetMapping("/admin/fault")
    public Map<String, Object> get() {
        return state();
    }

    @DeleteMapping("/admin/fault")
    public Map<String, Object> clear() {
        engine.clear();
        return state();
    }

    private Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("spec", engine.currentSpec());
        m.put("originEpochMs", engine.currentOriginMs());
        m.put("stats", engine.stats());
        return m;
    }
}
