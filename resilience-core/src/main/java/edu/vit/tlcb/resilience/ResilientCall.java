package edu.vit.tlcb.resilience;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Composition of Resilience4j {@link Retry} and {@link TimeLimiter} with a {@link Breaker}, for one
 * downstream dependency. Nesting, outermost first:
 *
 * <pre>
 *   Retry  ( Breaker ( TimeLimiter ( HTTP attempt ) ) )
 * </pre>
 *
 * which is Resilience4j's recommended order and mirrors the mesh: the retry policy wraps the per-try
 * timeout, and each retry attempt asks the breaker for permission again (like Envoy re-selecting a host
 * that is not ejected). A call rejected by an open breaker is <em>not</em> retried: immediately retrying
 * against a breaker that just said no would only burn the retry budget.
 *
 * <p>Failure classification: a {@link DownstreamException} (HTTP 5xx), a timeout and any other exception (for
 * example an I/O error) are failures; they feed the breaker and are retried (subject to {@link ResilienceSettings.RetryPolicy}).
 * HTTP 4xx and business outcomes are successes from the resilience point of view.
 *
 * <p>The same instance is used for all three systems under test: {@link NoopBreaker} stands in for "no
 * breaker", so the retry and timeout behaviour is held identical across systems.
 */
public final class ResilientCall {

    private final String name;
    private final Breaker breaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;
    private final ScheduledExecutorService scheduler;
    private final ExecutionObserver observer;

    public ResilientCall(String name, ResilienceSettings settings, Breaker breaker,
                         ScheduledExecutorService scheduler, ExecutionObserver observer) {
        this.name = name;
        this.breaker = breaker;
        this.scheduler = scheduler;
        this.observer = observer == null ? ExecutionObserver.NOOP : observer;
        this.timeLimiter = TimeLimiter.of(name, TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(settings.timeoutMs))
                .cancelRunningFuture(true)
                .build());
        ResilienceSettings.RetryPolicy policy = settings.retryOn;
        this.retry = Retry.of(name, RetryConfig.custom()
                .maxAttempts(settings.retries + 1)
                .waitDuration(Duration.ofMillis(settings.retryWaitMs))
                .retryOnException(t -> isRetryable(unwrap(t), policy))
                .build());
    }

    public Breaker breaker() {
        return breaker;
    }

    /**
     * Executes the call under the resilience policy.
     *
     * @param attemptFn starts one HTTP attempt; receives the zero-based attempt number
     */
    public <T> CompletionStage<T> execute(IntFunction<CompletionStage<T>> attemptFn) {
        AtomicInteger attemptNo = new AtomicInteger();
        Supplier<CompletionStage<T>> guarded = () -> guardedAttempt(attemptFn, attemptNo.getAndIncrement());
        return retry.decorateCompletionStage(scheduler, guarded).get();
    }

    private <T> CompletionStage<T> guardedAttempt(IntFunction<CompletionStage<T>> attemptFn, int attempt) {
        if (!breaker.tryAcquirePermission()) {
            return CompletableFuture.failedFuture(new BreakerOpenException(name));
        }
        long start = System.nanoTime();
        CompletableFuture<T> out = new CompletableFuture<>();
        CompletionStage<T> limited;
        try {
            limited = timeLimiter.executeCompletionStage(scheduler, () -> abortable(attemptFn, attempt));
        } catch (RuntimeException e) {
            breaker.releasePermission();
            return CompletableFuture.failedFuture(e);
        }
        limited.whenComplete((result, error) -> {
            long elapsed = System.nanoTime() - start;
            if (error == null) {
                breaker.onSuccess(elapsed);
                observer.onAttempt(elapsed, false);
                out.complete(result);
                return;
            }
            Throwable cause = unwrap(error);
            if (cause instanceof CancellationException) {
                breaker.releasePermission();
            } else {
                breaker.onFailure(elapsed, cause);
                observer.onAttempt(elapsed, true);
            }
            out.completeExceptionally(cause);
        });
        return out;
    }

    /**
     * Starts an attempt and returns a stand-in future for it. When the stand-in completes exceptionally
     * (typically because the TimeLimiter timed it out) the real attempt is <em>cancelled</em>. This is done
     * explicitly because for CompletionStages the TimeLimiter only completes the future exceptionally and
     * never cancels it, and an HTTP client aborts the in-flight request only on cancellation.
     */
    private static <T> CompletionStage<T> abortable(IntFunction<CompletionStage<T>> attemptFn, int attempt) {
        CompletableFuture<T> standIn = new CompletableFuture<>();
        CompletableFuture<T> real = attemptFn.apply(attempt).toCompletableFuture();
        real.whenComplete((r, e) -> {
            if (e == null) standIn.complete(r);
            else standIn.completeExceptionally(unwrap(e));
        });
        standIn.whenComplete((r, e) -> {
            if (e != null && !real.isDone()) real.cancel(true);
        });
        return standIn;
    }

    static boolean isRetryable(Throwable t, ResilienceSettings.RetryPolicy policy) {
        if (t instanceof BreakerOpenException || t instanceof CancellationException) return false;
        if (policy == ResilienceSettings.RetryPolicy.ISTIO_GATEWAY_ERROR && t instanceof DownstreamException d) {
            return d.status() == 502 || d.status() == 503 || d.status() == 504;
        }
        return true;
    }

    /** True if the throwable denotes a timeout (time limiter or HTTP client). */
    public static boolean isTimeout(Throwable t) {
        Throwable c = unwrap(t);
        return c instanceof TimeoutException || c instanceof HttpTimeoutException;
    }

    /** True if the call was rejected by an open circuit breaker (as opposed to failing downstream). */
    public static boolean isRejection(Throwable t) {
        return unwrap(t) instanceof BreakerOpenException;
    }

    static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }
}
