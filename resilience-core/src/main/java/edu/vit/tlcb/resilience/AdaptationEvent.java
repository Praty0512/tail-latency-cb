package edu.vit.tlcb.resilience;

/**
 * Audit-log entry for one threshold change: what changed, when, and the evidence the decision was based on.
 */
public record AdaptationEvent(
        long epochMs,
        double tSec,
        String breaker,
        ThresholdStrategy.Action action,
        int oldThreshold,
        int newThreshold,
        String reason,
        double p95Ms,
        double p99Ms,
        double p99Ewma,
        double slopeMsPerSec,
        double relativeSlopePerSec,
        long windowSamples) {
}
