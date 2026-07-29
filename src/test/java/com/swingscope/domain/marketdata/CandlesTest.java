package com.swingscope.domain.marketdata;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandlesTest {

    private static Candle bar(String date, String close) {
        return new Candle(LocalDate.parse(date), new BigDecimal(close), new BigDecimal(close),
                new BigDecimal(close), new BigDecimal(close), 1_000L);
    }

    @Test
    void exposesClosesInTheOrderGiven() {
        Candles candles = new Candles("AAPL", List.of(
                bar("2026-07-24", "208.00"),
                bar("2026-07-27", "211.00"),
                bar("2026-07-28", "214.25")));

        assertThat(candles.size()).isEqualTo(3);
        assertThat(candles.isEmpty()).isFalse();
        assertThat(candles.closes()).containsExactly(
                new BigDecimal("208.00"), new BigDecimal("211.00"), new BigDecimal("214.25"));
        assertThat(candles.latest().date()).isEqualTo(LocalDate.of(2026, 7, 28));
    }

    @Test
    void anEmptySeriesHasNoLatestBar() {
        Candles empty = new Candles("AAPL", List.of());

        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.size()).isZero();
        assertThat(empty.latest()).isNull();
        assertThat(empty.closes()).isEmpty();
    }

    @Test
    void theBarListIsDefensivelyCopied() {
        List<Candle> mutable = new ArrayList<>(List.of(bar("2026-07-28", "214.25")));
        Candles candles = new Candles("AAPL", mutable);

        mutable.add(bar("2026-07-29", "999.00"));

        assertThat(candles.size()).isEqualTo(1);
        assertThatThrownBy(() -> candles.bars().add(bar("2026-07-30", "1.00")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
