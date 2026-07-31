package com.swingscope.domain.levels;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything the level engine found for one symbol: the zones, the proposed stop and target, and
 * what it could not determine.
 *
 * <p>Nothing here is a recommendation to trade. It is arithmetic over past price, offered so the
 * human can confirm or overrule it quickly.
 *
 * @param unconfirmedTailBars the newest bars that cannot yet form a pivot — a level inside this
 *                            window would only be visible in hindsight
 */
public record LevelAnalysis(
        String symbol,
        BigDecimal price,
        BigDecimal atr,
        LevelSuggestion stop,
        LevelSuggestion target,
        List<PriceZone> supports,
        List<PriceZone> resistances,
        int barsAnalyzed,
        int unconfirmedTailBars,
        List<String> warnings
) {

    public LevelAnalysis {
        supports = supports == null ? List.of() : List.copyOf(supports);
        resistances = resistances == null ? List.of() : List.copyOf(resistances);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** True when both a stop and a target were found — the only case that can size a trade. */
    public boolean isComplete() {
        return stop.isPresent() && target.isPresent();
    }

    /** Reward:risk implied by the suggestions, or null when either is missing. */
    public BigDecimal impliedRatio() {
        if (!isComplete() || price == null) {
            return null;
        }
        BigDecimal risk = price.subtract(stop.value());
        BigDecimal reward = target.value().subtract(price);
        if (risk.signum() <= 0) {
            return null;
        }
        return reward.divide(risk, 2, java.math.RoundingMode.HALF_UP);
    }
}
