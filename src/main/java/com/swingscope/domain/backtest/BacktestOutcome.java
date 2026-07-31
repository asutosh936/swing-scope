package com.swingscope.domain.backtest;

/** How a replayed setup resolved. */
public enum BacktestOutcome {

    /** Price reached the target before the stop. */
    TARGET_FIRST("Target hit"),

    /** Price reached the stop before the target — including every ambiguous bar. */
    STOP_FIRST("Stopped out"),

    /** Neither level was touched inside the time stop. A real result: the trade went nowhere. */
    TIMEOUT("Timed out"),

    /**
     * The series ended before the time stop elapsed, so the outcome is unknown — a <em>censored</em>
     * observation, not a result. Every series produces these at its tail; counting them as timeouts
     * would understate both winners and losers, so they are excluded from every metric.
     */
    INCOMPLETE("Incomplete — data ran out"),

    /** The level engine refused to propose a stop or a target here. Not a trade, not a result. */
    NO_SUGGESTION("No suggestion"),

    /**
     * Levels were proposed but the setup could not have been entered — the entry price gapped to or
     * below the stop, leaving no risk distance. Excluded from performance, counted separately so
     * the rate is visible rather than silently dropped.
     */
    NOT_TAKEABLE("Not takeable");

    private final String label;

    BacktestOutcome(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Did this produce a measurable R multiple? */
    public boolean isResolved() {
        return this == TARGET_FIRST || this == STOP_FIRST || this == TIMEOUT;
    }
}
