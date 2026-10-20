package edu.vit.tlcb.inventory;

import edu.vit.tlcb.common.web.SimulatedWork;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stock lookup. The catalogue is a deterministic function of the SKU (no mutable state), so the service
 * is idempotent and every run sees identical stock levels.
 */
@RestController
public class InventoryController {

    private final long workIterations;

    public InventoryController(@Value("${app.work-iterations:100000}") long workIterations) {
        this.workIterations = workIterations;
    }

    @GetMapping("/api/inventory/{sku}")
    public ResponseEntity<Map<String, Object>> stock(@PathVariable String sku) {
        SimulatedWork.burn(workIterations);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", sku);
        // Stock in [10, 99]: always enough for the quantities the load generator orders (1-3), so with no
        // fault injected every order succeeds and any failure in an experiment is attributable to a fault.
        body.put("available", 10 + Math.floorMod(sku.hashCode() * 31 + 7, 90));
        return ResponseEntity.ok(body);
    }
}
