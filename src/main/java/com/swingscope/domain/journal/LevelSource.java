package com.swingscope.domain.journal;

/**
 * Where a trade's stop and target came from.
 *
 * <p>Recorded so the scorecard can eventually answer the only question that matters about Phase 6:
 * <em>do the computed levels perform better or worse than the ones you read off the chart?</em>
 * Without this the automation gets adopted on faith and its effect stays invisible.
 */
public enum LevelSource {

    /** The human set both levels — the Phase 1–5 behaviour. */
    HUMAN("Set by hand"),

    /** Taken exactly as the level engine proposed them. */
    SUGGESTED("Suggested, accepted as-is"),

    /** Suggested, then changed. Any difference at all counts — a one-cent tweak is a decision. */
    EDITED("Suggested, then adjusted");

    private final String label;

    LevelSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
