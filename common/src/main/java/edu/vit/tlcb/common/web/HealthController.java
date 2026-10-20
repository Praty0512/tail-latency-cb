package edu.vit.tlcb.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/** Liveness endpoint {@code GET /health} used by docker-compose health checks and the harness. */
@RestController
public class HealthController {

    private final String serviceName;

    public HealthController(@Value("${spring.application.name}") String serviceName) {
        this.serviceName = serviceName;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("service", serviceName);
        m.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        return m;
    }
}
