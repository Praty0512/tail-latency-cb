package edu.vit.tlcb.common.fault;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Servlet filter that applies the {@link FaultEngine}'s decision: it sleeps the servlet thread for the
 * injected delay (a faulty service really does hold its worker thread, like a hung dependency would)
 * and/or short-circuits with an error status.
 *
 * <p>Operational endpoints ({@code /health}, {@code /actuator}, {@code /admin}) are never faulted.
 */
public class FaultInjectionFilter extends OncePerRequestFilter {

    public static final String HEADER_KEY = "X-Request-Key";
    public static final String HEADER_ATTEMPT = "X-Attempt";
    public static final String HEADER_FAULT = "X-Fault-Injected";

    private static final Logger log = LoggerFactory.getLogger(FaultInjectionFilter.class);

    private final FaultEngine engine;

    public FaultInjectionFilter(FaultEngine engine) {
        this.engine = engine;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return p.startsWith("/admin") || p.startsWith("/actuator") || p.equals("/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        FaultEngine.Decision d = engine.decide(
                request.getRequestURI(),
                request.getHeader(HEADER_KEY),
                parseAttempt(request.getHeader(HEADER_ATTEMPT)),
                System.currentTimeMillis());

        if (d.isNone()) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader(HEADER_FAULT, String.join(",", d.firedRules()));
        if (d.delayMs() > 0) {
            try {
                Thread.sleep(d.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("fault delay interrupted");
            }
        }
        if (d.errorStatus() != null) {
            response.setStatus(d.errorStatus());
            response.setContentType("application/json");
            response.getOutputStream().write(
                    "{\"error\":\"injected_fault\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }

    private static int parseAttempt(String header) {
        if (header == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(header.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
