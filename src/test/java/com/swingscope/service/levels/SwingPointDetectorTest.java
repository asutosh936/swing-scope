package com.swingscope.service.levels;

import com.swingscope.domain.levels.SwingPoint;
import com.swingscope.domain.marketdata.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SwingPointDetectorTest {

    private final SwingPointDetector detector = new SwingPointDetector();

    /** Builds bars from (low, high) pairs; close sits mid-range and is irrelevant to pivots. */
    private static List<Candle> bars(double... lowHighPairs) {
        List<Candle> list = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < lowHighPairs.length; i += 2) {
            BigDecimal low = BigDecimal.valueOf(lowHighPairs[i]);
            BigDecimal high = BigDecimal.valueOf(lowHighPairs[i + 1]);
            BigDecimal mid = low.add(high).divide(BigDecimal.valueOf(2), 4, java.math.RoundingMode.HALF_UP);
            list.add(new Candle(date.plusDays(i / 2), mid, high, low, mid, 1_000_000L));
        }
        return list;
    }

    @Test
    @DisplayName("a V-shaped bottom is a swing low, hand-checked bar by bar")
    void findsASwingLow() {
        // lows:  10  9   8   7   8   9  10   <- index 3 is below the 3 bars either side
        List<Candle> series = bars(
                10, 12, 9, 11, 8, 10, 7, 9, 8, 10, 9, 11, 10, 12);

        List<SwingPoint> lows = detector.lows(series, 3);

        assertThat(lows).singleElement().satisfies(p -> {
            assertThat(p.barIndex()).isEqualTo(3);
            assertThat(p.price()).isEqualByComparingTo("7");
            assertThat(p.type()).isEqualTo(SwingPoint.Type.LOW);
            assertThat(p.date()).isEqualTo(LocalDate.of(2026, 1, 4));
        });
    }

    @Test
    @DisplayName("an inverted-V top is a swing high")
    void findsASwingHigh() {
        // highs: 10  11  12  13  12  11  10  <- index 3 is above the 3 bars either side
        List<Candle> series = bars(
                8, 10, 9, 11, 10, 12, 11, 13, 10, 12, 9, 11, 8, 10);

        List<SwingPoint> highs = detector.highs(series, 3);

        assertThat(highs).singleElement().satisfies(p -> {
            assertThat(p.barIndex()).isEqualTo(3);
            assertThat(p.price()).isEqualByComparingTo("13");
            assertThat(p.type()).isEqualTo(SwingPoint.Type.HIGH);
        });
    }

    @Test
    @DisplayName("strength widens the filter — a shallow dip survives n=1 but not n=3")
    void strengthControlsSensitivity() {
        // lows: 10 9 10 9 8 9 10 9 10 — index 4 is the deep low; 1 and 3 are shallow dips.
        List<Candle> series = bars(
                10, 12, 9, 11, 10, 12, 9, 11, 8, 10, 9, 11, 10, 12, 9, 11, 10, 12);

        assertThat(detector.lows(series, 1)).extracting(SwingPoint::barIndex)
                .containsExactly(1, 4, 7);
        assertThat(detector.lows(series, 3)).extracting(SwingPoint::barIndex)
                .containsExactly(4);
    }

    @Test
    @DisplayName("the last `strength` bars cannot be confirmed — a pivot you only see in hindsight")
    void doesNotConfirmPivotsInsideTheUnconfirmedTail() {
        // The lowest low is the second-to-last bar, but it has only 1 bar to its right.
        List<Candle> series = bars(
                10, 12, 9, 11, 10, 12, 11, 13, 12, 14, 5, 7, 9, 11);

        assertThat(detector.lows(series, 3)).isEmpty();
        assertThat(SwingPointDetector.unconfirmedTailBars(3)).isEqualTo(3);
    }

    @Test
    @DisplayName("detection reads only the list it is given — the guarantee a backtest relies on")
    void neverLooksBeyondTheSuppliedSeries() {
        List<Candle> full = bars(
                10, 12, 9, 11, 10, 12, 11, 13, 10, 12, 9, 11, 10, 12,
                // A dramatic low in the "future" — must not influence the earlier window.
                2, 4, 3, 5, 4, 6, 5, 7);
        List<Candle> asOfBarSix = full.subList(0, 7);

        List<SwingPoint> fromWindow = detector.lows(asOfBarSix, 3);

        assertThat(fromWindow).isEmpty();
        assertThat(detector.lows(full, 3)).extracting(SwingPoint::barIndex).contains(7);
    }

    @Test
    @DisplayName("a flat double bottom at an identical low is not a pivot — an ambiguous turn is no turn")
    void identicalLowsAreNotPivots() {
        List<Candle> series = bars(
                10, 12, 9, 11, 8, 10, 7, 9, 7, 9, 8, 10, 9, 11, 10, 12);

        assertThat(detector.lows(series, 3)).isEmpty();
    }

    @Test
    void returnsEmptyForSeriesTooShortToConfirmAnything() {
        assertThat(detector.detect(bars(10, 12, 9, 11, 8, 10), 3)).isEmpty();
        assertThat(detector.detect(null, 3)).isEmpty();
        assertThat(detector.detect(List.of(), 3)).isEmpty();
        assertThat(SwingPointDetector.minimumBars(3)).isEqualTo(7);
    }

    @Test
    void rejectsANonsenseStrength() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> detector.detect(bars(10, 12), 0))
                .withMessageContaining("at least 1");
    }

    @Test
    void pivotsComeBackInChronologicalOrder() {
        List<Candle> series = bars(
                10, 12, 9, 11, 8, 10, 7, 9, 8, 10, 9, 11, 12, 16,
                9, 11, 8, 10, 7, 9, 6, 8, 7, 9, 8, 10, 9, 11);

        List<SwingPoint> pivots = detector.detect(series, 3);

        assertThat(pivots).isNotEmpty();
        assertThat(pivots).extracting(SwingPoint::barIndex).isSorted();
    }

    @Test
    void defaultStrengthIsThree() {
        List<Candle> series = bars(
                10, 12, 9, 11, 8, 10, 7, 9, 8, 10, 9, 11, 10, 12);

        assertThat(detector.detect(series)).isEqualTo(detector.detect(series, 3));
        assertThat(SwingPointDetector.DEFAULT_STRENGTH).isEqualTo(3);
    }
}
