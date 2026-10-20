package edu.vit.tlcb.order;

/**
 * Abstraction over "call one downstream service with whatever resilience policy is currently active".
 * The order logic only sees this interface; the policy (none / static breaker / adaptive breaker) is
 * swapped at runtime behind it.
 */
public interface OutboundCall {

    /** Name of the downstream service, e.g. {@code payment-service}. */
    String target();

    /**
     * Executes the call, blocking the caller until a final outcome is known (after any retries).
     *
     * @return the downstream response (HTTP 2xx/4xx)
     * @throws DependencyUnavailableException if the call ultimately failed (5xx, timeout, I/O error) or was
     *                                        rejected by an open circuit breaker
     */
    DownstreamClient.Response execute(String method, String path, String jsonBody, String requestKey);

    /** Raised when a dependency could not be used. */
    class DependencyUnavailableException extends RuntimeException {
        private final String target;
        private final boolean rejectedByBreaker;

        public DependencyUnavailableException(String target, String message, Throwable cause, boolean rejectedByBreaker) {
            super(message, cause);
            this.target = target;
            this.rejectedByBreaker = rejectedByBreaker;
        }

        public String target() {
            return target;
        }

        public boolean rejectedByBreaker() {
            return rejectedByBreaker;
        }
    }
}
