package com.swingscope.domain.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One daily OHLCV bar. */
public record Candle(
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        Long volume
) {
}
