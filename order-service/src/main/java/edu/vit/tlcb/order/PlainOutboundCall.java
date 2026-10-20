package edu.vit.tlcb.order;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Unprotected call: one attempt, a fixed timeout, no retry, no circuit breaker. */
public class PlainOutboundCall implements OutboundCall {

    private final DownstreamClient client;
    private final long timeoutMs;

    public PlainOutboundCall(DownstreamClient client, long timeoutMs) {
        this.client = client;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String target() {
        return client.name();
    }

    @Override
    public DownstreamClient.Response execute(String method, String path, String jsonBody, String requestKey) {
        var future = client.send(method, path, jsonBody, requestKey, 0);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DependencyUnavailableException(target(), "timeout after " + timeoutMs + " ms", e, false);
        } catch (ExecutionException e) {
            throw new DependencyUnavailableException(target(), String.valueOf(e.getCause()), e.getCause(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new DependencyUnavailableException(target(), "interrupted", e, false);
        }
    }
}
