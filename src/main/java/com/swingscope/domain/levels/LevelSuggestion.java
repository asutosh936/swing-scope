package com.swingscope.domain.levels;

import java.math.BigDecimal;

/**
 * A proposed stop or target — <strong>or an explicit refusal to propose one</strong>.
 *
 * <p>The refusal is the important half. Any pivot detector will emit something for any series; a
 * tool that always produces a level teaches you to trust it uniformly, when some charts have clean
 * structure and others have none. {@code value == null} with a reason is a real answer.
 */
public record LevelSuggestion(
        BigDecimal value,
        String rationale,
        Confidence confidence,
        PriceZone zone,
        Source source
) {

    /**
     * Where the number came from. Kept distinct because the two have different standing: the
     * structural level is derived from price behaviour, the fallback is a volatility rule that
     * ignores the chart entirely. A reader must never mistake one for the other.
     */
    public enum Source {
        /** A support/resistance zone found in the price series. */
        STRUCTURE,
        /** No clean structure — a volatility-derived level, to be checked against the chart. */
        ATR_FALLBACK,
        /** No level proposed at all. */
        NONE
    }

    public boolean isFallback() {
        return source == Source.ATR_FALLBACK;
    }

    public enum Confidence {
        /** Three or more touches, tested recently. */
        HIGH,
        /** Enough touches to count, but older or thinner. */
        MEDIUM,
        /** Meets the bar and no more. Treat as a hint. */
        LOW,
        /** No suggestion — see the rationale. */
        NONE
    }

    /** No clean level. The reason is shown to the user verbatim. */
    public static LevelSuggestion none(String reason) {
        return new LevelSuggestion(null, reason, Confidence.NONE, null, Source.NONE);
    }

    /** A volatility-derived level used because no structure was found. Always LOW confidence. */
    public static LevelSuggestion fallback(BigDecimal value, String rationale) {
        return new LevelSuggestion(value, rationale, Confidence.LOW, null, Source.ATR_FALLBACK);
    }

    public boolean isPresent() {
        return value != null;
    }
}
