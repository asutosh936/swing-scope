package com.swingscope.domain.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Everything the mechanical filters need about one symbol, assembled across providers.
 *
 * <p>Every judgement field here is a fact, not advice: support/resistance and the trigger candle
 * stay human. {@code inUptrend} is just the arithmetic of the plan's trend test.
 *
 * @param inUptrend      price &gt; EMA50 AND EMA50 &gt; EMA200; null when there is not enough history
 * @param bigMover       |changePercent| &gt; 5 — news risk
 * @param earningsWithin3Days next earnings date falls inside the 3-calendar-day block window
 * @param volume        today's running session volume — partial while the market is open
 * @param averageVolume typical daily volume. <strong>This</strong> is the liquidity measure; today's
 *                      volume is not, because mid-session it is only part of a day.
 * @param warnings       non-fatal gaps, e.g. an optional provider being unavailable
 */
public record MarketSnapshot(
        String symbol,
        BigDecimal price,
        BigDecimal changePercent,
        BigDecimal ema20,
        BigDecimal ema50,
        BigDecimal ema200,
        Long volume,
        Long averageVolume,
        BigDecimal marketCap,
        LocalDate nextEarningsDate,
        Boolean inUptrend,
        boolean bigMover,
        boolean earningsWithin3Days,
        int candlesAvailable,
        java.util.List<String> warnings
) {

    public MarketSnapshot {
        warnings = warnings == null ? java.util.List.of() : java.util.List.copyOf(warnings);
    }
}
