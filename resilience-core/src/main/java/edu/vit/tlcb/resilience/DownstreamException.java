package edu.vit.tlcb.resilience;

/**
 * A downstream call that must count as a <em>failure</em> for retry / circuit-breaker purposes:
 * an HTTP 5xx response. (Timeouts and I/O errors are represented by their own exception types.)
 * HTTP 4xx and business-level results are not failures and never produce this exception.
 */
public class DownstreamException extends RuntimeException {

    private final int status;

    public DownstreamException(int status, String message) {
        super(message, null, false, false); // no stack trace: cheap to create under fault storms
        this.status = status;
    }

    public int status() {
        return status;
    }
}
