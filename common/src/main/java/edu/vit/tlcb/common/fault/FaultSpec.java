package edu.vit.tlcb.common.fault;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A complete fault-injection configuration as posted to {@code POST /admin/fault}.
 *
 * <p>Example (3 s delay on half of the requests between t=60 s and t=120 s, 30 % HTTP 500 for the
 * whole run):
 * <pre>
 * { "seed": 42,
 *   "rules": [
 *     {"id":"slow","type":"DELAY","delayMs":3000,"probability":0.5,"startOffsetMs":60000,"endOffsetMs":120000},
 *     {"id":"err","type":"ERROR","status":500,"probability":0.3} ] }
 * </pre>
 *
 * @param enabled        master switch (default true)
 * @param seed           seed for the deterministic random decisions
 * @param originEpochMs  wall-clock origin that rule window offsets are relative to; defaults to the time
 *                       the spec is applied. The harness passes the same origin to every service so
 *                       fault windows are synchronised.
 * @param rules          ordered rule list
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FaultSpec(Boolean enabled, Long seed, Long originEpochMs, List<FaultRule> rules) {

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    public long seedOrDefault() {
        return seed == null ? 0L : seed;
    }

    public List<FaultRule> rulesOrEmpty() {
        return rules == null ? List.of() : rules;
    }

    /** Validates the spec; returns an error message or null if valid. */
    public String validate() {
        for (FaultRule r : rulesOrEmpty()) {
            String err = r.validate();
            if (err != null) return err;
        }
        return null;
    }
}
