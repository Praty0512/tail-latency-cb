package edu.vit.tlcb.resilience;

/** Pass-through "breaker" used for the no-breaker system: always permits, never changes state. */
public final class NoopBreaker implements Breaker {

    private final String name;

    public NoopBreaker(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean tryAcquirePermission() {
        return true;
    }

    @Override
    public void onSuccess(long durationNanos) {
    }

    @Override
    public void onFailure(long durationNanos, Throwable error) {
    }

    @Override
    public void releasePermission() {
    }

    @Override
    public BreakerState state() {
        return BreakerState.NONE;
    }

    @Override
    public int failureThreshold() {
        return -1;
    }

    @Override
    public void reset() {
    }

    @Override
    public void addListener(BreakerListener listener) {
    }
}
