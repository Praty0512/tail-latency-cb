package edu.vit.tlcb.payment;

import edu.vit.tlcb.common.web.SimulatedWork;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simulated card charge. Stateless and idempotent for a given {@code orderId} (the transaction id is a
 * pure function of it), which is what makes retrying a charge safe in the experiments. Business-level
 * declines are returned as HTTP 200 with {@code status=DECLINED}: they are not infrastructure failures
 * and must not trip a circuit breaker.
 */
@RestController
public class PaymentController {

    public record ChargeRequest(String orderId, Double amount, String cardLast4) {
    }

    private final long workIterations;

    public PaymentController(@Value("${app.work-iterations:200000}") long workIterations) {
        this.workIterations = workIterations;
    }

    @PostMapping("/api/payments/charge")
    public ResponseEntity<Map<String, Object>> charge(@RequestBody ChargeRequest req) {
        SimulatedWork.burn(workIterations);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", req.orderId());
        double amount = req.amount() == null ? 0 : req.amount();
        boolean approved = amount > 0 && amount <= 10_000;
        body.put("status", approved ? "APPROVED" : "DECLINED");
        body.put("transactionId", Long.toHexString(req.orderId() == null ? 0 : req.orderId().hashCode() * 0x9E3779B1L));
        return ResponseEntity.ok(body);
    }
}
