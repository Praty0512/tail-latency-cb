package edu.vit.tlcb.common.fault;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One fault-injection rule. Rules are evaluated in order for every business request.
 *
 * <ul>
 *   <li>{@code DELAY}: fixed delay of {@code delayMs}.</li>
 *   <li>{@code RANDOM_DELAY}: delay drawn from {@code distribution} (UNIFORM[minMs,maxMs],
 *       EXPONENTIAL(meanMs) or LOGNORMAL(medianMs, sigma)), capped at {@code maxDelayMs}.</li>
 *   <li>{@code ERROR}: respond with HTTP {@code status} (default 500) instead of calling the handler.</li>
 * </ul>
 *
 * <p>{@code probability} is the chance (0..1) that the rule fires for a request; {@code startOffsetMs} /
 * {@code endOffsetMs} define the fault window relative to the spec's origin (null = unbounded).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FaultRule(
        String id,
        Type type,
        Double probability,
        Long delayMs,
        Distribution distribution,
        Double minMs,
        Double maxMs,
        Double meanMs,
        Double medianMs,
        Double sigma,
        Long maxDelayMs,
        Integer status,
        Long startOffsetMs,
        Long endOffsetMs,
        String pathPrefix) {

    public enum Type { DELAY, RANDOM_DELAY, ERROR }

    public enum Distribution { UNIFORM, EXPONENTIAL, LOGNORMAL }

    public double probabilityOrDefault() {
        return probability == null ? 1.0 : probability;
    }

    public String pathPrefixOrDefault() {
        return pathPrefix == null ? "/api/" : pathPrefix;
    }

    public int statusOrDefault() {
        return status == null ? 500 : status;
    }

    public long maxDelayOrDefault() {
        return maxDelayMs == null ? 30_000L : maxDelayMs;
    }

    /** Validates the rule; returns an error message or null if valid. */
    public String validate() {
        if (type == null) return "rule '" + id + "': type is required";
        double p = probabilityOrDefault();
        if (p < 0 || p > 1) return "rule '" + id + "': probability must be within [0,1]";
        if (startOffsetMs != null && endOffsetMs != null && endOffsetMs <= startOffsetMs) {
            return "rule '" + id + "': endOffsetMs must be greater than startOffsetMs";
        }
        switch (type) {
            case DELAY:
                if (delayMs == null || delayMs < 0) return "rule '" + id + "': delayMs >= 0 required";
                break;
            case RANDOM_DELAY:
                if (distribution == null) return "rule '" + id + "': distribution required";
                if (distribution == Distribution.UNIFORM
                        && (minMs == null || maxMs == null || minMs < 0 || maxMs < minMs)) {
                    return "rule '" + id + "': UNIFORM needs 0 <= minMs <= maxMs";
                }
                if (distribution == Distribution.EXPONENTIAL && (meanMs == null || meanMs <= 0)) {
                    return "rule '" + id + "': EXPONENTIAL needs meanMs > 0";
                }
                if (distribution == Distribution.LOGNORMAL
                        && (medianMs == null || medianMs <= 0 || sigma == null || sigma < 0)) {
                    return "rule '" + id + "': LOGNORMAL needs medianMs > 0 and sigma >= 0";
                }
                break;
            case ERROR:
                if (statusOrDefault() < 400 || statusOrDefault() > 599) {
                    return "rule '" + id + "': status must be 4xx/5xx";
                }
                break;
            default:
                break;
        }
        return null;
    }
}
