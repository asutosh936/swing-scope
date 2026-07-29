package com.swingscope.domain.marketdata;

import java.math.BigDecimal;

/**
 * A provider-neutral price quote.
 *
 * @param changePercent today's move in percent, e.g. {@code 5.3} for +5.3%. The big-mover rule
 *                      compares its absolute value against 5.
 */
public record Quote(
        String symbol,
        BigDecimal price,
        BigDecimal previousClose,
        BigDecimal change,
        BigDecimal changePercent,
        Long volume,
        Long averageVolume
) {
}
