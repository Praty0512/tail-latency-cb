package edu.vit.tlcb.order;

import edu.vit.tlcb.common.fault.FaultInjectionFilter;
import edu.vit.tlcb.resilience.DownstreamException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Thin asynchronous HTTP client for one downstream service, built on the JDK {@link HttpClient}.
 *
 * <p>The JDK client is used deliberately: its {@code sendAsync} future is <em>cancellable</em>, so when the
 * Resilience4j {@code TimeLimiter} gives up on a call, the underlying request is aborted and no worker
 * thread stays parked on a hung socket (a blocking RestTemplate/Apache call would keep its thread until the
 * slow server answered, which would leak threads under a 3 s delay fault and distort the experiment).
 *
 * <p>HTTP 5xx responses complete the future exceptionally with {@link DownstreamException}; 4xx responses
 * are returned normally because they are business outcomes, not infrastructure failures.
 */
public class DownstreamClient {

    /** Minimal response view. */
    public record Response(int status, String body) {
    }

    private final String name;
    private final URI baseUri;
    private final HttpClient http;
    private final Duration hardTimeout;

    public DownstreamClient(String name, String baseUrl, Duration connectTimeout, Duration hardTimeout) {
        this.name = name;
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.hardTimeout = hardTimeout;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
    }

    public String name() {
        return name;
    }

    /**
     * @param method     GET or POST
     * @param path       path beginning with '/'
     * @param jsonBody   request body for POST, may be null
     * @param requestKey stable per-request key forwarded for deterministic fault injection
     * @param attempt    zero-based attempt number (retries carry attempt &gt; 0)
     */
    public CompletableFuture<Response> send(String method, String path, String jsonBody, String requestKey, int attempt) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUri + path))
                .timeout(hardTimeout) // last-resort cap; the TimeLimiter is the real per-try timeout
                .header(FaultInjectionFilter.HEADER_KEY, requestKey)
                .header(FaultInjectionFilter.HEADER_ATTEMPT, Integer.toString(attempt));
        if ("POST".equals(method)) {
            b.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
        } else {
            b.GET();
        }
        return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    if (r.statusCode() >= 500) {
                        throw new DownstreamException(r.statusCode(), name + " returned HTTP " + r.statusCode());
                    }
                    return new Response(r.statusCode(), r.body());
                });
    }
}
