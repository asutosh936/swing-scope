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
        PriceZone zone
) {

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
        return new LevelSuggestion(null, reason, Confidence.NONE, null);
    }

    public boolean isPresent() {
        return value != null;
    }
}
