package com.swingscope.service.levels;

import com.swingscope.domain.marketdata.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Average True Range — how much this stock typically moves in a day, in dollars.
 *
 * <p>Phase 6 uses it for two jobs: padding a stop below support so ordinary noise doesn't trigger
 * it, and deciding how close two pivots must be to count as the same level. Both need a measure of
 * "normal movement" that adapts per symbol — 50 cents is noise on one stock and a week's range on
 * another.
 *
 * <h2>Definition</h2>
 * True range for a bar is the largest of:
 * <ul>
 *   <li>{@code high − low} (today's range)</li>
 *   <li>{@code |high − previousClose|} (gap up then fade)</li>
 *   <li>{@code |low − previousClose|} (gap down then recover)</li>
 * </ul>
 * The first bar has no previous close, so its true range is simply {@code high − low}.
 *
 * <p>ATR is then Wilder's smoothing of that series: seed with the simple average of the first
 * {@code period} true ranges, then {@code atr = (previous × (period − 1) + currentTr) ÷ period}.
 * This is the standard used by charting platforms — deliberately the same choice as
 * {@code EmaCalculator}, so the numbers match what the user sees.
 */
@Component
public class AtrCalculator {

    private static final Logger log = LoggerFactory.getLogger(AtrCalculator.class);

    public static final int DEFAULT_PERIOD = 14;

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final int RESULT_SCALE = 4;

    public BigDecimal atr(List<Candle> bars) {
        return atr(bars, DEFAULT_PERIOD);
    }

    /**
     * @return the latest ATR, or null when there are fewer than {@code period + 1} bars — null
     *         rather than a guess, matching how {@link com.swingscope.service.marketdata.EmaCalculator}
     *         handles short history
     */
    public BigDecimal atr(List<Candle> bars, int period) {
        List<BigDecimal> series = atrSeries(bars, period);
        return series.isEmpty() ? null : series.get(series.size() - 1);
    }

    /** The full ATR series, one value from bar {@code period} onwards. Useful for backtests. */
    public List<BigDecimal> atrSeries(List<Candle> bars, int period) {
        if (period < 1) {
            throw new IllegalArgumentException("ATR period must be at least 1, got " + period);
        }
        // period true ranges need period+1 bars, since the first bar has no previous close.
        if (bars == null || bars.size() <= period) {
            log.debug("Not enough bars for ATR{}: have {}, need {}",
                    period, bars == null ? 0 : bars.size(), period + 1);
            return List.of();
        }

        List<BigDecimal> trueRanges = trueRanges(bars);

        // Seed on the first `period` true ranges. Index 0 is the first bar's bare high−low, which
        // Wilder includes; it is the only true range without a previous close to consider.
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(trueRanges.get(i));
        }
        BigDecimal atr = sum.divide(BigDecimal.valueOf(period), MC);

        List<BigDecimal> series = new ArrayList<>(trueRanges.size() - period + 1);
        series.add(atr.setScale(RESULT_SCALE, RoundingMode.HALF_UP));

        BigDecimal periodValue = BigDecimal.valueOf(period);
        BigDecimal periodLessOne = BigDecimal.valueOf(period - 1L);
        for (int i = period; i < trueRanges.size(); i++) {
            atr = atr.multiply(periodLessOne, MC).add(trueRanges.get(i)).divide(periodValue, MC);
            series.add(atr.setScale(RESULT_SCALE, RoundingMode.HALF_UP));
        }

        log.debug("ATR{} over {} bars -> {}", period, bars.size(), series.get(series.size() - 1));
        return List.copyOf(series);
    }

    /** True range per bar, oldest first. The first entry is just that bar's high − low. */
    List<BigDecimal> trueRanges(List<Candle> bars) {
        List<BigDecimal> ranges = new ArrayList<>(bars.size());
        for (int i = 0; i < bars.size(); i++) {
            Candle bar = bars.get(i);
            BigDecimal highLow = bar.high().subtract(bar.low());
            if (i == 0) {
                ranges.add(highLow);
                continue;
            }
            BigDecimal previousClose = bars.get(i - 1).close();
            BigDecimal highToClose = bar.high().subtract(previousClose).abs();
            BigDecimal lowToClose = bar.low().subtract(previousClose).abs();
            ranges.add(highLow.max(highToClose).max(lowToClose));
        }
        return ranges;
    }
}
