package com.swingscope.domain.journal;

/**
 * The chart pattern the human read. Recorded so the scorecard can eventually answer "which setup
 * actually works for me" — the tool never infers or suggests one.
 */
public enum SetupType {

    BREAKOUT("Breakout"),
    PULLBACK("Pullback"),
    REVERSAL("Reversal"),
    RANGE("Range"),
    OTHER("Other");

    private final String label;

    SetupType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
