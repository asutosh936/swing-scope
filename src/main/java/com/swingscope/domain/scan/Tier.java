package com.swingscope.domain.scan;

/**
 * Where a candidate lands after the mechanical filters.
 *
 * <p>A tier is a sorting of the work, not a recommendation. Tier 1 means "worth your chart time
 * first" — never "buy this". The human still reads every chart and sets stop and target.
 */
public enum Tier {

    /** Trend test passed, liquid and established: chart these first. */
    TIER1("Tier 1", "Trend intact, liquid, established — chart these first"),

    /** Trend test passed but thinner or smaller: tradeable, watch the fill. */
    TIER2("Tier 2", "Trend intact but thinner or smaller — mind the fill"),

    /** Trend intact, but something makes it risky today: big move or imminent earnings. */
    TIER3("Tier 3", "Trend intact but event risk today — usually skip"),

    /** Failed the trend test outright. */
    SKIP("Skip", "Failed the trend test"),

    /** Data could not be fetched — not a verdict about the stock. */
    UNAVAILABLE("Unavailable", "No data — this says nothing about the stock");

    private final String label;
    private final String description;

    Tier(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    /** Only Tier 1 and 2 offer the "plan this trade" action. */
    public boolean isTradeable() {
        return this == TIER1 || this == TIER2;
    }
}
