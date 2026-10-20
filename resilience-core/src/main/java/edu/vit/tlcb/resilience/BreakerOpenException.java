package edu.vit.tlcb.resilience;

/** The call was rejected without being attempted because the circuit breaker is open (or probing is saturated). */
public class BreakerOpenException extends RuntimeException {

    private final String breaker;

    public BreakerOpenException(String breaker) {
        super("circuit breaker '" + breaker + "' is not permitting calls", null, false, false);
        this.breaker = breaker;
    }

    public String breaker() {
        return breaker;
    }
}
