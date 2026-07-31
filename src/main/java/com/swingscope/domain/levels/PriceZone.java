package com.swingscope.domain.levels;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * A price band where the stock has turned before — support below the current price, resistance
 * above it.
 *
 * <p>A zone rather than a single price, because real levels are fuzzy: three reversals at 38.40,
 * 38.55 and 38.31 are one shelf, not three lines. Pivots within a fraction of ATR collapse together.
 *
 * @param touches           how many pivots formed this zone. More touches, more times the market
 *                          has agreed the level matters
 * @param barsSinceLastTouch recency — a level last tested a year ago is weaker than one tested
 *                          last month
 * @param strength          touches plus a recency bonus, rounded to 2dp. A comparable score, not a
 *                          probability, and emphatically not a forecast
 */
public record PriceZone(
        Type type,
        BigDecimal low,
        BigDecimal high,
        BigDecimal center,
        int touches,
        int lastTouchBarIndex,
        int barsSinceLastTouch,
        BigDecimal strength,
        List<SwingPoint> pivots
) {

    public enum Type {
        SUPPORT,
        RESISTANCE
    }

    public PriceZone {
        pivots = pivots == null ? List.of() : List.copyOf(pivots);
    }

    /** How wide the zone is, in dollars. */
    public BigDecimal width() {
        return high.subtract(low);
    }

    /** Distance from a price to this zone's centre, as a signed percentage of that price. */
    public BigDecimal percentFrom(BigDecimal price) {
        if (price == null || price.signum() == 0) {
            return null;
        }
        return center.subtract(price)
                .multiply(new BigDecimal("100"))
                .divide(price, 2, RoundingMode.HALF_UP);
    }
}
