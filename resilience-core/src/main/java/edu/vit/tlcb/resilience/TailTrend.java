package edu.vit.tlcb.resilience;

/**
 * Trend of the tail latency at one evaluation tick.
 *
 * @param p95Ms              p95 of the rolling window
 * @param p99Ms              p99 of the rolling window
 * @param p99Ewma            exponentially weighted moving average of the p99 over successive evaluations
 * @param slopeMsPerSec      least-squares slope of the last N p99 values, in ms per second
 * @param relativeSlopePerSec the slope divided by the mean p99 level over those N values (floored), i.e. "how
 *                           many multiples of its own level the p99 is gaining per second"; scale-free so the
 *                           same threshold works for a 500 ms and a 3 s timeout
 * @param points             number of p99 values the slope was fitted on
 */
public record TailTrend(double p95Ms, double p99Ms, double p99Ewma, double slopeMsPerSec,
                        double relativeSlopePerSec, int points) {
}
