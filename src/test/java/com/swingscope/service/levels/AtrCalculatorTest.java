package com.swingscope.service.levels;

import com.swingscope.domain.marketdata.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AtrCalculatorTest {

    private final AtrCalculator calculator = new AtrCalculator();

    /** Bars from (high, low, close) triples. */
    private static List<Candle> bars(double... hlc) {
        List<Candle> list = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < hlc.length; i += 3) {
            list.add(new Candle(date.plusDays(i / 3),
                    BigDecimal.valueOf(hlc[i + 2]),          // open — unused by ATR
                    BigDecimal.valueOf(hlc[i]),              // high
                    BigDecimal.valueOf(hlc[i + 1]),          // low
                    BigDecimal.valueOf(hlc[i + 2]),          // close
                    1_000_000L));
        }
        return list;
    }

    @Test
    @DisplayName("true range picks the largest of the three candidates, hand-checked")
    void trueRangeCoversGapsInBothDirections() {
        List<Candle> series = bars(
                12, 10, 11,     // bar 0: no previous close -> TR = 12−10 = 2
                13, 11, 12,     // bar 1: range 2, |13−11|=2, |11−11|=0 -> 2
                20, 18, 19,     // bar 2: gap UP. range 2, |20−12|=8, |18−12|=6 -> 8
                9,  7,  8);     // bar 3: gap DOWN. range 2, |9−19|=10, |7−19|=12 -> 12

        List<BigDecimal> ranges = calculator.trueRanges(series);

        assertThat(ranges).hasSize(4);
        assertThat(ranges.get(0)).isEqualByComparingTo("2");
        assertThat(ranges.get(1)).isEqualByComparingTo("2");
        assertThat(ranges.get(2)).isEqualByComparingTo("8");   // gap up dominates the bar's range
        assertThat(ranges.get(3)).isEqualByComparingTo("12");  // gap down dominates
    }

    @Test
    @DisplayName("ATR seeds on the simple average of the first `period` true ranges")
    void seedsWithTheSimpleAverage() {
        // Four bars, all true range 2 except the third which is 6. Period 3.
        // TRs: 2, 2, 6  -> seed = (2+2+6)/3 = 3.3333
        List<Candle> series = bars(
                12, 10, 11,
                13, 11, 12,
                18, 12, 17,     // range 6, |18−12|=6, |12−12|=0 -> 6
                19, 17, 18);    // range 2, |19−17|=2, |17−17|=0 -> 2

        List<BigDecimal> atr = calculator.atrSeries(series, 3);

        assertThat(atr).hasSize(2);
        assertThat(atr.get(0)).isEqualByComparingTo("3.3333");

        // Wilder step: (3.3333... × 2 + 2) / 3 = 8.6666../3 = 2.8889
        assertThat(atr.get(1)).isEqualByComparingTo("2.8889");
    }

    @Test
    @DisplayName("a constant-range series has an ATR equal to that range")
    void constantRangeGivesThatRange() {
        List<Candle> series = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 1, 1);
        // Every bar: high 11, low 9, close 10 -> range 2, and no gaps, so every TR is exactly 2.
        for (int i = 0; i < 30; i++) {
            series.add(new Candle(date.plusDays(i), BigDecimal.TEN, BigDecimal.valueOf(11),
                    BigDecimal.valueOf(9), BigDecimal.TEN, 1_000_000L));
        }

        assertThat(calculator.atr(series, 14)).isEqualByComparingTo("2.0000");
    }

    @Test
    @DisplayName("a volatile stock reports a larger ATR than a quiet one — the point of the measure")
    void reflectsRelativeVolatility() {
        List<Candle> quiet = new ArrayList<>();
        List<Candle> volatile_ = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 30; i++) {
            quiet.add(new Candle(date.plusDays(i), BigDecimal.valueOf(100),
                    BigDecimal.valueOf(100.5), BigDecimal.valueOf(99.5),
                    BigDecimal.valueOf(100), 1_000_000L));
            volatile_.add(new Candle(date.plusDays(i), BigDecimal.valueOf(100),
                    BigDecimal.valueOf(105), BigDecimal.valueOf(95),
                    BigDecimal.valueOf(100), 1_000_000L));
        }

        assertThat(calculator.atr(quiet, 14)).isEqualByComparingTo("1.0000");
        assertThat(calculator.atr(volatile_, 14)).isEqualByComparingTo("10.0000");
    }

    @Test
    @DisplayName("null rather than a guess when history is short — same contract as EmaCalculator")
    void shortHistoryReturnsNull() {
        // 14 bars can only yield 14 true ranges; ATR(14) needs 15 bars to have one to smooth onto.
        List<Candle> fourteen = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 14; i++) {
            fourteen.add(new Candle(date.plusDays(i), BigDecimal.TEN, BigDecimal.valueOf(11),
                    BigDecimal.valueOf(9), BigDecimal.TEN, 1_000_000L));
        }

        assertThat(calculator.atr(fourteen, 14)).isNull();
        assertThat(calculator.atrSeries(fourteen, 14)).isEmpty();
        assertThat(calculator.atr(null, 14)).isNull();
        assertThat(calculator.atr(List.of(), 14)).isNull();
    }

    @Test
    void rejectsANonsensePeriod() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculator.atrSeries(bars(12, 10, 11), 0))
                .withMessageContaining("at least 1");
    }

    @Test
    void defaultPeriodIsFourteen() {
        List<Candle> series = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 40; i++) {
            series.add(new Candle(date.plusDays(i), BigDecimal.TEN, BigDecimal.valueOf(11),
                    BigDecimal.valueOf(9), BigDecimal.TEN, 1_000_000L));
        }

        assertThat(calculator.atr(series)).isEqualByComparingTo(calculator.atr(series, 14));
        assertThat(AtrCalculator.DEFAULT_PERIOD).isEqualTo(14);
    }

    @Test
    @DisplayName("ATR reads only the bars supplied — the no-lookahead guarantee")
    void neverLooksBeyondTheSuppliedSeries() {
        List<Candle> full = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 20; i++) {
            full.add(new Candle(date.plusDays(i), BigDecimal.TEN, BigDecimal.valueOf(11),
                    BigDecimal.valueOf(9), BigDecimal.TEN, 1_000_000L));
        }
        // A wild bar in the "future" that must not affect an as-of-bar-19 reading.
        full.add(new Candle(date.plusDays(20), BigDecimal.TEN, BigDecimal.valueOf(200),
                BigDecimal.ONE, BigDecimal.TEN, 1_000_000L));

        BigDecimal asOfNineteen = calculator.atr(full.subList(0, 20), 14);
        BigDecimal withTheFuture = calculator.atr(full, 14);

        assertThat(asOfNineteen).isEqualByComparingTo("2.0000");
        assertThat(withTheFuture).isGreaterThan(asOfNineteen);
    }
}
