package edu.vit.tlcb.order;

import edu.vit.tlcb.resilience.ResilientCall;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbound call protected by Retry + circuit breaker + TimeLimiter (see {@link ResilientCall}). The calling
 * servlet thread blocks until the asynchronous pipeline reaches a final outcome; that outcome is bounded by
 * {@code (retries + 1) * (timeout + retry wait)}, so the safety limit below is never reached in practice.
 */
public class ResilientOutboundCall implements OutboundCall {

    private final DownstreamClient client;
    private final ResilientCall resilient;
    private final long safetyLimitMs;

    public ResilientOutboundCall(DownstreamClient client, ResilientCall resilient, long safetyLimitMs) {
        this.client = client;
        this.resilient = resilient;
        this.safetyLimitMs = safetyLimitMs;
    }

    @Override
    public String target() {
        return client.name();
    }

    @Override
    public DownstreamClient.Response execute(String method, String path, String jsonBody, String requestKey) {
        CompletionStage<DownstreamClient.Response> stage =
                resilient.execute(attempt -> client.send(method, path, jsonBody, requestKey, attempt));
        try {
            return stage.toCompletableFuture().get(safetyLimitMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            boolean rejected = ResilientCall.isRejection(cause);
            String what = rejected ? "circuit breaker open"
                    : ResilientCall.isTimeout(cause) ? "timeout" : String.valueOf(cause.getMessage());
            throw new DependencyUnavailableException(target(), what, cause, rejected);
        } catch (TimeoutException e) {
            throw new DependencyUnavailableException(target(), "safety limit exceeded", e, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DependencyUnavailableException(target(), "interrupted", e, false);
        }
    }
}
