package com.swingscope.domain.journal;

/**
 * Where a journalled trade stands.
 *
 * <p>Only {@link #CLOSED_WIN} and {@link #CLOSED_LOSS} are "completed trades": they alone feed the
 * win rate, expectancy and the graduation count. A {@link #REJECTED} setup was never taken —
 * the rules turned it down — so it is a record of discipline, not a result. A {@link #SCRATCH} was exited flat and a
 * {@link #NO_FILL} never happened, so neither is evidence about the strategy.
 */
public enum TradeStatus {

    PLANNED("Planned"),
    /** Saved from the calculator after the rules refused it. Kept as a record of discipline. */
    REJECTED("Rejected by the rules"),
    FILLED("Filled"),
    NO_FILL("No fill"),
    CLOSED_WIN("Closed — win"),
    CLOSED_LOSS("Closed — loss"),
    SCRATCH("Scratch");

    private final String label;

    TradeStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Terminal states — nothing transitions out of these. */
    public boolean isTerminal() {
        return this == NO_FILL || this == REJECTED
                || this == CLOSED_WIN || this == CLOSED_LOSS || this == SCRATCH;
    }

    /** A completed trade that counts toward the scorecard and the graduation gate. */
    public boolean isCountedTrade() {
        return this == CLOSED_WIN || this == CLOSED_LOSS;
    }

    public boolean isClosed() {
        return this == CLOSED_WIN || this == CLOSED_LOSS || this == SCRATCH;
    }
}
