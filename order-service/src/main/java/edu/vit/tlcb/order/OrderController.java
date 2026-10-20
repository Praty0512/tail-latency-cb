package edu.vit.tlcb.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.vit.tlcb.common.fault.FaultInjectionFilter;
import edu.vit.tlcb.common.web.SimulatedWork;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Order placement: (1) check stock at inventory-service, (2) charge at payment-service. Both calls are
 * synchronous from the caller's point of view, as in the system under study.
 *
 * <p>Failure semantics (deliberately close to what an Istio-fronted service would return): when a
 * dependency is unusable or its breaker is open, the order fails fast with HTTP 503. There is no
 * fallback, so any latency benefit of a breaker is paid for in error rate and the analysis reports both.
 */
@RestController
public class OrderController {

    public record OrderRequest(String sku, Integer quantity, Double amount, String cardLast4) {
    }

    private final OutboundCallRegistry calls;
    private final ObjectMapper mapper;
    private final long workIterations;
    private final AtomicLong anonymousKeys = new AtomicLong();

    public OrderController(OutboundCallRegistry calls, ObjectMapper mapper,
                           @Value("${app.work-iterations:50000}") long workIterations) {
        this.calls = calls;
        this.mapper = mapper;
        this.workIterations = workIterations;
    }

    @PostMapping("/api/orders")
    public ResponseEntity<Map<String, Object>> placeOrder(
            @RequestBody OrderRequest req,
            @RequestHeader(value = FaultInjectionFilter.HEADER_KEY, required = false) String headerKey) throws Exception {

        SimulatedWork.burn(workIterations);
        String key = headerKey != null ? headerKey : "anon-" + anonymousKeys.incrementAndGet();
        int qty = req.quantity() == null ? 1 : req.quantity();

        // 1. stock check
        DownstreamClient.Response stock = calls.inventory().execute("GET", "/api/inventory/" + req.sku(), null, key);
        JsonNode stockJson = mapper.readTree(stock.body());
        if (stockJson.path("available").asInt(0) < qty) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "out_of_stock", "sku", req.sku()));
        }

        // 2. payment
        String orderId = "ord-" + key;
        String chargeBody = mapper.writeValueAsString(Map.of(
                "orderId", orderId, "amount", req.amount() == null ? 0 : req.amount(),
                "cardLast4", req.cardLast4() == null ? "0000" : req.cardLast4()));
        DownstreamClient.Response pay = calls.payment().execute("POST", "/api/payments/charge", chargeBody, key);
        JsonNode payJson = mapper.readTree(pay.body());
        if (!"APPROVED".equals(payJson.path("status").asText())) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(Map.of("error", "payment_declined", "orderId", orderId));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("status", "CONFIRMED");
        body.put("sku", req.sku());
        body.put("quantity", qty);
        body.put("transactionId", payJson.path("transactionId").asText());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @ExceptionHandler(OutboundCall.DependencyUnavailableException.class)
    public ResponseEntity<Map<String, Object>> dependencyUnavailable(OutboundCall.DependencyUnavailableException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "dependency_unavailable");
        body.put("dependency", e.target());
        body.put("rejectedByBreaker", e.rejectedByBreaker());
        body.put("detail", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
