package edu.vit.tlcb.common.metrics;

import java.util.Map;

/**
 * Supplies the current circuit-breaker state of each downstream target, so that the metrics export can
 * record {@code cb_state} next to every latency sample without depending on the resilience library.
 */
public interface CbStateProvider {

    /** State of one breaker at one instant. {@code threshold} is the live trip threshold (-1 if none). */
    record Status(String state, int threshold) {
        public static final Status NONE = new Status("NA", -1);
    }

    /** Current status keyed by downstream target name (e.g. {@code payment-service}). */
    Map<String, Status> statuses();
}
