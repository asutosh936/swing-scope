package com.swingscope.service.marketdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EmaCalculatorTest {

    private final EmaCalculator calculator = new EmaCalculator();

    private static List<BigDecimal> closes(String... values) {
        return Stream.of(values).map(BigDecimal::new).toList();
    }

    @Test
    @DisplayName("EMA5 matches a by-hand calculation step for step")
    void reproducesAHandCheckedEma() {
        // Ten closes. Seed = SMA of the first five = (10+11+12+13+14)/5 = 12.0000
        // k = 2/(5+1) = 0.333333...
        //   bar 6 (15): 12      + (15 − 12)      × k = 13.0000
        //   bar 7 (16): 13      + (16 − 13)      × k = 14.0000
        //   bar 8 (17): 14      + (17 − 14)      × k = 15.0000
        //   bar 9 (18): 15      + (18 − 15)      × k = 16.0000
        //   bar 10 (19): 16     + (19 − 16)      × k = 17.0000
        List<BigDecimal> closes = closes("10", "11", "12", "13", "14", "15", "16", "17", "18", "19");

        List<BigDecimal> series = calculator.emaSeries(closes, 5);

        assertThat(series).hasSize(6);
        assertThat(series.get(0)).isEqualByComparingTo("12.0000");   // the SMA seed
        assertThat(series.get(1)).isEqualByComparingTo("13.0000");
        assertThat(series.get(2)).isEqualByComparingTo("14.0000");
        assertThat(series.get(3)).isEqualByComparingTo("15.0000");
        assertThat(series.get(4)).isEqualByComparingTo("16.0000");
        assertThat(series.get(5)).isEqualByComparingTo("17.0000");
        assertThat(calculator.ema(closes, 5)).isEqualByComparingTo("17.0000");
    }

    @Test
    @DisplayName("EMA3 over an uneven series matches hand arithmetic to 4dp")
    void handCheckedWithUnevenPrices() {
        // Seed = (22.27 + 22.19 + 22.08) / 3 = 22.1800
        // k = 2/4 = 0.5
        //   22.17: 22.1800 + (22.17 − 22.1800) × 0.5 = 22.1750
        //   22.18: 22.1750 + (22.18 − 22.1750) × 0.5 = 22.1775
        //   22.13: 22.1775 + (22.13 − 22.1775) × 0.5 = 22.1538 (22.15375 → HALF_UP)
        List<BigDecimal> closes = closes("22.27", "22.19", "22.08", "22.17", "22.18", "22.13");

        List<BigDecimal> series = calculator.emaSeries(closes, 3);

        assertThat(series.get(0)).isEqualByComparingTo("22.1800");
        assertThat(series.get(1)).isEqualByComparingTo("22.1750");
        assertThat(series.get(2)).isEqualByComparingTo("22.1775");
        assertThat(series.get(3)).isEqualByComparingTo("22.1538");
    }

    @Test
    @DisplayName("a flat series has an EMA equal to the price at every step")
    void flatSeriesIsStable() {
        List<BigDecimal> flat = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            flat.add(new BigDecimal("42.00"));
        }

        assertThat(calculator.ema(flat, 50)).isEqualByComparingTo("42.0000");
        assertThat(calculator.ema(flat, 20)).isEqualByComparingTo("42.0000");
    }

    @Test
    @DisplayName("a rising series puts EMA20 above EMA50 above EMA200 — the uptrend shape")
    void risingSeriesOrdersTheEmas() {
        List<BigDecimal> rising = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            rising.add(new BigDecimal(100 + i));
        }

        BigDecimal ema20 = calculator.ema(rising, 20);
        BigDecimal ema50 = calculator.ema(rising, 50);
        BigDecimal ema200 = calculator.ema(rising, 200);

        assertThat(ema20).isGreaterThan(ema50);
        assertThat(ema50).isGreaterThan(ema200);
    }

    @Test
    @DisplayName("period 1 makes the EMA the close itself")
    void periodOneTracksPriceExactly() {
        assertThat(calculator.ema(closes("10", "20", "30"), 1)).isEqualByComparingTo("30.0000");
    }

    @Test
    @DisplayName("exactly `period` closes yields just the SMA seed")
    void exactlyEnoughHistoryGivesTheSeedOnly() {
        List<BigDecimal> series = calculator.emaSeries(closes("10", "20", "30"), 3);

        assertThat(series).hasSize(1);
        assertThat(series.get(0)).isEqualByComparingTo("20.0000");
    }

    @Test
    @DisplayName("too little history returns nothing rather than a misleading number")
    void insufficientHistoryReturnsEmpty() {
        assertThat(calculator.emaSeries(closes("10", "11"), 5)).isEmpty();
        assertThat(calculator.ema(closes("10", "11"), 5)).isNull();
        assertThat(calculator.emaSeries(List.of(), 5)).isEmpty();
        assertThat(calculator.ema(null, 5)).isNull();
    }

    @Test
    @DisplayName("a nonsense period is rejected")
    void rejectsAnInvalidPeriod() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculator.ema(closes("10", "11"), 0))
                .withMessageContaining("at least 1");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculator.ema(closes("10", "11"), -5));
    }

    @Test
    @DisplayName("the returned series is immutable")
    void seriesIsImmutable() {
        List<BigDecimal> series = calculator.emaSeries(closes("10", "11", "12"), 2);

        assertThat(series).isNotEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> series.add(BigDecimal.ONE))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
