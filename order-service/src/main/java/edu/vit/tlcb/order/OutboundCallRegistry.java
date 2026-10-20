package edu.vit.tlcb.order;

import edu.vit.tlcb.common.metrics.LatencyMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Holds the {@link OutboundCall}s for the two downstream services. Every call installed here is wrapped
 * in an {@link InstrumentedOutboundCall}, so latency is recorded no matter which resilience policy
 * (none / static breaker / adaptive breaker) is active behind it.
 */
@Component
public class OutboundCallRegistry {

    private final LatencyMetrics metrics;
    private final DownstreamClient inventoryClient;
    private final DownstreamClient paymentClient;
    private volatile OutboundCall inventory;
    private volatile OutboundCall payment;

    public OutboundCallRegistry(
            LatencyMetrics metrics,
            @Value("${downstream.inventory.url}") String inventoryUrl,
            @Value("${downstream.payment.url}") String paymentUrl,
            @Value("${downstream.hard-timeout-ms:30000}") long hardTimeoutMs) {
        this.metrics = metrics;
        this.inventoryClient = new DownstreamClient("inventory-service", inventoryUrl,
                Duration.ofSeconds(2), Duration.ofMillis(hardTimeoutMs));
        this.paymentClient = new DownstreamClient("payment-service", paymentUrl,
                Duration.ofSeconds(2), Duration.ofMillis(hardTimeoutMs));
        install(new PlainOutboundCall(inventoryClient, 10_000), new PlainOutboundCall(paymentClient, 10_000));
    }

    public OutboundCall inventory() {
        return inventory;
    }

    public OutboundCall payment() {
        return payment;
    }

    public DownstreamClient inventoryClient() {
        return inventoryClient;
    }

    public DownstreamClient paymentClient() {
        return paymentClient;
    }

    /** Installs new call policies (atomically per target) and instruments them. */
    public void install(OutboundCall inventoryCall, OutboundCall paymentCall) {
        this.inventory = new InstrumentedOutboundCall(inventoryCall, metrics);
        this.payment = new InstrumentedOutboundCall(paymentCall, metrics);
    }
}
