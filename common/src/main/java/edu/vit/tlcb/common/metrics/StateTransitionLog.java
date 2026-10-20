package edu.vit.tlcb.common.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Timestamped record of every circuit-breaker state transition (CLOSED/OPEN/HALF_OPEN), kept for the
 * per-run export and logged at INFO so the transitions are visible in the service log as well.
 */
public class StateTransitionLog implements RunExportContributor {

    public record Entry(long epochMs, double tSec, String breaker, String from, String to, String reason) {
    }

    private static final Logger log = LoggerFactory.getLogger(StateTransitionLog.class);

    private final List<Entry> entries = new ArrayList<>();
    private volatile long originEpochMs = System.currentTimeMillis();

    public void record(String breaker, String from, String to, String reason) {
        long now = System.currentTimeMillis();
        Entry e = new Entry(now, (now - originEpochMs) / 1000.0, breaker, from, to, reason);
        synchronized (entries) {
            entries.add(e);
        }
        log.info("CB-TRANSITION breaker={} {} -> {} ({})", breaker, from, to, reason);
    }

    @Override
    public String key() {
        return "cbTransitions";
    }

    @Override
    public Object snapshot() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    @Override
    public void reset(long originEpochMs) {
        this.originEpochMs = originEpochMs;
        synchronized (entries) {
            entries.clear();
        }
    }
}
