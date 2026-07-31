package com.swingscope.domain.levels;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A pivot in the price series — a local turning point.
 *
 * <p>A swing <em>low</em> is a bar whose low sits below the lows of the {@code strength} bars on
 * both sides; a swing <em>high</em> is the mirror. These are the raw material for support and
 * resistance: price turned here once, so it may matter again.
 *
 * @param barIndex position in the series the pivot was found in — kept so a backtest can prove it
 *                 never used a bar at or after the entry it is testing
 * @param price    the low of a swing low, the high of a swing high
 */
public record SwingPoint(int barIndex, LocalDate date, BigDecimal price, Type type) {

    public enum Type {
        HIGH,
        LOW
    }

    public boolean isLow() {
        return type == Type.LOW;
    }

    public boolean isHigh() {
        return type == Type.HIGH;
    }
}
