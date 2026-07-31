package com.swingscope.service.levels;

import com.swingscope.config.LevelProperties;
import com.swingscope.domain.levels.LevelAnalysis;
import com.swingscope.domain.levels.LevelSuggestion;
import com.swingscope.domain.levels.PriceZone;
import com.swingscope.domain.marketdata.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercised through {@link LevelSuggestionService#analyse} — the pure entry point — so no market
 * data is fetched and the series is fully controlled.
 */
class LevelSuggestionServiceTest {

    private final LevelProperties properties = new LevelProperties(null, null, null, null, null, null, null, null);
    private final SwingPointDetector detector = new SwingPointDetector();
    private final AtrCalculator atr = new AtrCalculator();
    private final PriceLevelService levels = new PriceLevelService(detector, atr, properties);
    private final LevelSuggestionService service =
            new LevelSuggestionService(null, levels, atr, properties);

    private static Candle bar(LocalDate date, double low, double high, double close) {
        return new Candle(date, BigDecimal.valueOf(close), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), 1_000_000L);
    }

    /**
     * A series that oscillates between a support shelf near 40 and a resistance shelf near 60,
     * finishing mid-range at 50. Each leg is 10 bars, so pivots are cleanly confirmed at strength 3.
     */
    private static List<Candle> oscillating(int cycles) {
        List<Candle> bars = new ArrayList<>();
        LocalDate date = LocalDate.of(2025, 1, 1);
        int day = 0;
        for (int c = 0; c < cycles; c++) {
            double[] legDown = {58, 55, 52, 48, 44, 41, 40.2, 41, 44, 48};
            double[] legUp = {52, 55, 58, 59.8, 59, 57, 54, 51, 49, 50};
            for (double p : legDown) {
                bars.add(bar(date.plusDays(day++), p - 1, p + 1, p));
            }
            for (double p : legUp) {
                bars.add(bar(date.plusDays(day++), p - 1, p + 1, p));
            }
        }
        return bars;
    }

    // ---------------------------------------------------------------------------- happy path

    @Test
    @DisplayName("proposes a stop below support and a target at the near edge of resistance")
    void proposesBothLevels() {
        List<Candle> bars = oscillating(5);
        BigDecimal price = BigDecimal.valueOf(50);

        LevelAnalysis analysis = service.analyse("TEST", bars, price);

        assertThat(analysis.stop().isPresent()).isTrue();
        assertThat(analysis.target().isPresent()).isTrue();

        // The series has two support shelves — one near 48 where each up-leg stalls, and a
        // deeper one near 40. The NEAREST below price is correctly chosen, not the deepest.
        assertThat(analysis.supports().size()).isGreaterThan(1);
        assertThat(analysis.supports().get(0).center())
                .as("nearest-first ordering")
                .isGreaterThan(analysis.supports().get(1).center());

        // Whichever shelf is chosen, the stop sits below it and below price, and stays positive.
        assertThat(analysis.stop().value()).isLessThan(analysis.stop().zone().low());
        assertThat(analysis.stop().value()).isLessThan(price);
        assertThat(analysis.stop().value()).isGreaterThan(BigDecimal.ZERO);

        // Target sits above price and at-or-below the resistance shelf top (~60.8).
        assertThat(analysis.target().value()).isGreaterThan(price);
        assertThat(analysis.target().value()).isLessThanOrEqualTo(BigDecimal.valueOf(61));

        assertThat(analysis.isComplete()).isTrue();
        assertThat(analysis.impliedRatio()).isNotNull();
    }

    @Test
    @DisplayName("the rationale states the evidence, not just the number")
    void rationaleExplainsItself() {
        LevelAnalysis analysis = service.analyse("TEST", oscillating(5), BigDecimal.valueOf(50));

        assertThat(analysis.stop().rationale())
                .contains("support at")
                .contains("touches")
                .contains("ATR");
        assertThat(analysis.target().rationale())
                .contains("resistance at")
                .contains("near edge");
    }

    @Test
    @DisplayName("the stop is placed below support by the ATR buffer, not at it")
    void stopSitsBelowTheShelf() {
        LevelAnalysis analysis = service.analyse("TEST", oscillating(5), BigDecimal.valueOf(50));
        PriceZone support = analysis.stop().zone();

        assertThat(analysis.stop().value()).isLessThan(support.low());
    }

    @Test
    void warnsThatTheNewestBarsCannotFormAPivot() {
        LevelAnalysis analysis = service.analyse("TEST", oscillating(5), BigDecimal.valueOf(50));

        assertThat(analysis.unconfirmedTailBars()).isEqualTo(3);
        assertThat(analysis.warnings()).anySatisfy(w ->
                assertThat(w).contains("cannot form a pivot yet"));
    }

    // ------------------------------------------------------------------------------ refusals

    @Test
    @DisplayName("too little history is refused outright rather than guessed at")
    void refusesOnShortHistory() {
        List<Candle> bars = oscillating(1);   // 20 bars, below the 60-bar floor

        LevelAnalysis analysis = service.analyse("TEST", bars, BigDecimal.valueOf(50));

        assertThat(analysis.stop().isPresent()).isFalse();
        assertThat(analysis.target().isPresent()).isFalse();
        assertThat(analysis.stop().confidence()).isEqualTo(LevelSuggestion.Confidence.NONE);
        assertThat(analysis.stop().rationale()).contains("only 20 bars").contains("need 60");
        assertThat(analysis.isComplete()).isFalse();
    }

    @Test
    @DisplayName("a relentless uptrend has no support below — refuse rather than invent one")
    void refusesWhenNoSupportExistsBelow() {
        List<Candle> bars = new ArrayList<>();
        LocalDate date = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < 120; i++) {           // strictly rising: no confirmed swing low
            double p = 20 + i;
            bars.add(bar(date.plusDays(i), p - 0.5, p + 0.5, p));
        }

        LevelAnalysis analysis = service.analyse("TEST", bars, BigDecimal.valueOf(200));

        assertThat(analysis.stop().isPresent()).isFalse();
        assertThat(analysis.stop().rationale()).contains("no support zone");
    }

    @Test
    @DisplayName("nothing overhead means no target — the tool says so instead of picking a number")
    void refusesWhenNothingOverhead() {
        LevelAnalysis analysis = service.analyse("TEST", oscillating(5), BigDecimal.valueOf(500));

        assertThat(analysis.target().isPresent()).isFalse();
        assertThat(analysis.target().rationale()).contains("no resistance zone");
    }

    @Test
    @DisplayName("a stop further than maxStopPercent is refused — too wide to size")
    void refusesAStopThatIsTooFarAway() {
        // Support near 40 but price at 200 -> a stop ~80% away, far beyond the 15% limit.
        LevelAnalysis analysis = service.analyse("TEST", oscillating(5), BigDecimal.valueOf(200));

        assertThat(analysis.stop().isPresent()).isFalse();
        assertThat(analysis.stop().rationale()).contains("wider than the 15%").contains("your own read");
    }

    @Test
    void refusesWithoutAUsablePrice() {
        LevelAnalysis analysis = service.analyse("TEST", oscillating(5), null);

        assertThat(analysis.stop().isPresent()).isFalse();
        assertThat(analysis.stop().rationale()).contains("no usable current price");
    }

    @Test
    void handlesAnEmptySeries() {
        LevelAnalysis analysis = service.analyse("TEST", List.of(), BigDecimal.valueOf(50));

        assertThat(analysis.barsAnalyzed()).isZero();
        assertThat(analysis.isComplete()).isFalse();
        assertThat(analysis.impliedRatio()).isNull();
    }

    // ---------------------------------------------------------------------------- confidence

    @Test
    @DisplayName("confidence rises with touches and recency, and never lies about a stale level")
    void confidenceReflectsTouchesAndRecency() {
        PriceZone strong = zone(4, 10);
        PriceZone manyButStale = zone(4, 200);
        PriceZone fewButRecent = zone(2, 10);
        PriceZone fewAndStale = zone(2, 200);

        assertThat(LevelSuggestionService.confidenceOf(strong))
                .isEqualTo(LevelSuggestion.Confidence.HIGH);
        assertThat(LevelSuggestionService.confidenceOf(manyButStale))
                .isEqualTo(LevelSuggestion.Confidence.MEDIUM);
        assertThat(LevelSuggestionService.confidenceOf(fewButRecent))
                .isEqualTo(LevelSuggestion.Confidence.MEDIUM);
        assertThat(LevelSuggestionService.confidenceOf(fewAndStale))
                .isEqualTo(LevelSuggestion.Confidence.LOW);
    }

    private static PriceZone zone(int touches, int barsSince) {
        return new PriceZone(PriceZone.Type.SUPPORT, BigDecimal.valueOf(40), BigDecimal.valueOf(41),
                BigDecimal.valueOf(40.5), touches, 100, barsSince, BigDecimal.valueOf(touches), List.of());
    }

    // ----------------------------------------------------------------------- no lookahead

    @Test
    @DisplayName("analysis reads only the bars handed to it — the backtest guarantee")
    void neverLooksBeyondTheSuppliedBars() {
        List<Candle> full = new ArrayList<>(oscillating(5));
        int asOf = full.size();
        // A dramatic collapse in the "future" that would create a much lower support shelf.
        LocalDate date = LocalDate.of(2030, 1, 1);
        for (int i = 0; i < 30; i++) {
            full.add(bar(date.plusDays(i), 5, 6, 5.5));
        }

        LevelAnalysis asOfToday = service.analyse("TEST", full.subList(0, asOf), BigDecimal.valueOf(50));
        LevelAnalysis withFuture = service.analyse("TEST", full, BigDecimal.valueOf(50));

        assertThat(asOfToday.stop().value()).isGreaterThan(BigDecimal.valueOf(35));
        assertThat(withFuture.supports().size()).isNotEqualTo(0);
        // The future collapse must not have influenced the as-of reading.
        assertThat(asOfToday.supports()).noneSatisfy(z ->
                assertThat(z.center()).isLessThan(BigDecimal.valueOf(10)));
    }
}
