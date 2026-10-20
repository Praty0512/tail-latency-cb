package edu.vit.tlcb.order;

import edu.vit.tlcb.common.metrics.LatencyMetrics;

/**
 * Decorator that records the latency and outcome of every <em>logical</em> outbound call (what the order
 * handler experiences: retries, backoff and fast-fail rejections included). Works for any call policy.
 * HTTP 4xx answers are returned normally by the delegate and are therefore counted as successful calls.
 */
public class InstrumentedOutboundCall implements OutboundCall {

    private final OutboundCall delegate;
    private final LatencyMetrics metrics;

    public InstrumentedOutboundCall(OutboundCall delegate, LatencyMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
        metrics.declare(LatencyMetrics.OUTBOUND, delegate.target());
    }

    @Override
    public String target() {
        return delegate.target();
    }

    @Override
    public DownstreamClient.Response execute(String method, String path, String jsonBody, String requestKey) {
        long start = System.nanoTime();
        try {
            DownstreamClient.Response r = delegate.execute(method, path, jsonBody, requestKey);
            metrics.outbound(target(), System.nanoTime() - start, false, false);
            return r;
        } catch (DependencyUnavailableException e) {
            metrics.outbound(target(), System.nanoTime() - start, true, e.rejectedByBreaker());
            throw e;
        }
    }
}
