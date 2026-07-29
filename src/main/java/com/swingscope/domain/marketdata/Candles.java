package com.swingscope.domain.marketdata;

import java.math.BigDecimal;
import java.util.List;

/**
 * A daily candle series in <strong>chronological order</strong> — oldest first, newest last.
 *
 * <p>Providers are not consistent about this (Twelve Data returns newest-first), so clients must
 * normalise before constructing. The EMA calculation depends on the order being right.
 */
public record Candles(String symbol, List<Candle> bars) {

    public Candles {
        bars = List.copyOf(bars);
    }

    public int size() {
        return bars.size();
    }

    public boolean isEmpty() {
        return bars.isEmpty();
    }

    /** Closing prices, oldest first — the input the EMA calculation expects. */
    public List<BigDecimal> closes() {
        return bars.stream().map(Candle::close).toList();
    }

    /** The most recent bar, or null when the series is empty. */
    public Candle latest() {
        return bars.isEmpty() ? null : bars.get(bars.size() - 1);
    }
}
