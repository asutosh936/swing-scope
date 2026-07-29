package com.swingscope.service.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * Standard exponential moving average, computed in-app so the numbers match the chart the user is
 * reading rather than whatever a provider's indicator endpoint decides to do.
 *
 * <p>The convention here is the common one, and the one E*TRADE and TradingView use by default:
 * seed with the simple average of the first {@code period} closes, then walk forward applying
 * {@code close × k + previous × (1 − k)} with {@code k = 2 / (period + 1)}.
 */
@Component
public class EmaCalculator {

    private static final Logger log = LoggerFactory.getLogger(EmaCalculator.class);

    /** Working precision — well beyond the 4 dp the result is reported at. */
    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final int RESULT_SCALE = 4;
    private static final BigDecimal TWO = new BigDecimal("2");

    /**
     * @param closes closing prices in chronological order, oldest first
     * @param period EMA period, e.g. 50
     * @return the latest EMA value, or null when there are fewer than {@code period} closes
     */
    public BigDecimal ema(List<BigDecimal> closes, int period) {
        List<BigDecimal> series = emaSeries(closes, period);
        return series.isEmpty() ? null : series.get(series.size() - 1);
    }

    /**
     * The full EMA series, one value per close from index {@code period - 1} onwards. Useful for
     * charting and for asserting intermediate values in tests.
     *
     * @return an empty list when there is not enough history
     */
    public List<BigDecimal> emaSeries(List<BigDecimal> closes, int period) {
        if (period < 1) {
            throw new IllegalArgumentException("EMA period must be at least 1, got " + period);
        }
        if (closes == null || closes.size() < period) {
            log.debug("Not enough history for EMA{}: have {} closes, need {}",
                    period, closes == null ? 0 : closes.size(), period);
            return List.of();
        }

        // BigDecimal.TWO is Java 19+; this codebase targets 17.
        BigDecimal multiplier = TWO.divide(BigDecimal.valueOf(period + 1L), MC);

        // Seed: simple average of the first `period` closes.
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(closes.get(i));
        }
        BigDecimal ema = sum.divide(BigDecimal.valueOf(period), MC);

        List<BigDecimal> series = new java.util.ArrayList<>(closes.size() - period + 1);
        series.add(ema.setScale(RESULT_SCALE, RoundingMode.HALF_UP));

        for (int i = period; i < closes.size(); i++) {
            ema = closes.get(i).subtract(ema).multiply(multiplier, MC).add(ema);
            series.add(ema.setScale(RESULT_SCALE, RoundingMode.HALF_UP));
        }

        log.debug("EMA{} over {} closes -> {}", period, closes.size(), series.get(series.size() - 1));
        return List.copyOf(series);
    }
}
