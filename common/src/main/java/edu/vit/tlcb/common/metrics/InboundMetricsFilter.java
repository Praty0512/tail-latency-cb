package edu.vit.tlcb.common.metrics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Times every business request ({@code /api/**}) from the outermost point of the filter chain, i.e. it
 * includes any delay injected by the fault filter. Only 5xx responses count as errors: a 409 out-of-stock
 * or a 402 payment decline is a correct business outcome.
 *
 * <p>The endpoint label is {@code METHOD /api/<resource>[/*]} (the first two path segments), which keeps
 * cardinality bounded and identical for requests short-circuited by the fault filter.
 */
public class InboundMetricsFilter extends OncePerRequestFilter {

    private final LatencyMetrics metrics;

    public InboundMetricsFilter(LatencyMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
            failed = response.getStatus() >= 500;
        } catch (ServletException | IOException | RuntimeException e) {
            failed = true;
            throw e;
        } finally {
            metrics.inbound(label(request), System.nanoTime() - start, failed);
        }
    }

    static String label(HttpServletRequest request) {
        String[] seg = request.getRequestURI().split("/");
        // seg[0] is "" because the path starts with '/'
        StringBuilder sb = new StringBuilder(request.getMethod()).append(' ');
        for (int i = 1; i < seg.length && i <= 2; i++) sb.append('/').append(seg[i]);
        if (seg.length > 3) sb.append("/*");
        return sb.toString();
    }
}
