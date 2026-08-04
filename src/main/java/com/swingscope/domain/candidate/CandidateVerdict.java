package com.swingscope.domain.candidate;

/** The outcome of auto-analysing one scanned candidate. */
public enum CandidateVerdict {

    /** Clears the reward:risk minimum and sizes to at least one share. */
    PASS("Passes your rules"),

    /** Levels were available, but the setup breaks a rule. The reason says which. */
    FAIL("Fails a rule"),

    /**
     * No levels could be derived, so there was nothing to analyse. The row is kept and states what
     * the human must supply — hiding it would imply the tool had finished when it had not.
     */
    NEEDS_LEVELS("Needs your levels");

    private final String label;

    CandidateVerdict(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isActionable() {
        return this == PASS;
    }
}
