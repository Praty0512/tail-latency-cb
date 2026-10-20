package edu.vit.tlcb.resilience;

/**
 * Adaptation switched off: the threshold always stays at its baseline. Running the adaptive breaker with this
 * strategy is the ablation that isolates the effect of the adaptation from the custom state machine.
 */
public final class FixedThresholdStrategy implements ThresholdStrategy {

    @Override
    public Decision decide(Input in) {
        return new Decision(in.baselineThreshold(), Action.HOLD, "adaptation disabled");
    }

    @Override
    public void reset() {
    }
}
