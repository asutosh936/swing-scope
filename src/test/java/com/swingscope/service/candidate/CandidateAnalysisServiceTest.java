package com.swingscope.service.candidate;

import com.swingscope.config.AnalysisProperties;
import com.swingscope.config.LevelProperties;
import com.swingscope.config.TradingRules;
import com.swingscope.domain.candidate.AnalysisConfidence;
import com.swingscope.domain.candidate.CandidateAnalysis;
import com.swingscope.domain.candidate.CandidateVerdict;
import com.swingscope.domain.marketdata.Candle;
import com.swingscope.domain.marketdata.Candles;
import com.swingscope.domain.scan.Tier;
import com.swingscope.domain.scan.TieredStock;
import com.swingscope.service.TradeCalculatorService;
import com.swingscope.service.levels.AtrCalculator;
import com.swingscope.service.levels.LevelSuggestionService;
import com.swingscope.service.levels.PriceLevelService;
import com.swingscope.service.levels.SwingPointDetector;
import com.swingscope.service.marketdata.MarketDataService;
import com.swingscope.service.marketdata.UnknownSymbolException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CandidateAnalysisServiceTest {

    private final TradingRules rules = new TradingRules(null, null, null);
    private final AnalysisProperties analysisProperties =
            new AnalysisProperties(null, new BigDecimal("500"), new BigDecimal("5.00"));
    private final LevelProperties levelProperties =
            new LevelProperties(null, null, null, null, null, null, null, null, null, null, null);
    private final AtrCalculator atr = new AtrCalculator();
    private final MarketDataService marketData = mock(MarketDataService.class);

    private CandidateAnalysisService service() {
        PriceLevelService levels = new PriceLevelService(new SwingPointDetector(), atr, levelProperties);
        return new CandidateAnalysisService(
                new LevelSuggestionService(marketData, levels, atr, levelProperties),
                new TradeCalculatorService(rules), analysisProperties, rules);
    }

    private static Candle bar(LocalDate d, double low, double high, double close) {
        return new Candle(d, BigDecimal.valueOf(close), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), 1_000_000L);
    }

    /** Support near 40, resistance near 60, finishing mid-range. */
    private static List<Candle> oscillating(int cycles) {
        List<Candle> bars = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 1);
        int day = 0;
        for (int c = 0; c < cycles; c++) {
            for (double p : new double[]{58, 55, 52, 48, 44, 41, 40.2, 41, 44, 48}) {
                bars.add(bar(date.plusDays(day++), p - 1, p + 1, p));
            }
            for (double p : new double[]{52, 55, 58, 59.8, 59, 57, 54, 51, 49, 50}) {
                bars.add(bar(date.plusDays(day++), p - 1, p + 1, p));
            }
        }
        return bars;
    }

    private static TieredStock stock(String symbol, String price, Boolean uptrend, String capMillions) {
        return new TieredStock(symbol, Tier.TIER1, "trend intact, liquid and established",
                price == null ? null : new BigDecimal(price), new BigDecimal("1.20"),
                new BigDecimal("49"), new BigDecimal("47"), new BigDecimal("45"),
                new BigDecimal("6.38"), 5_000_000L, 5_000_000L,
                capMillions == null ? null : new BigDecimal(capMillions),
                null, uptrend, false, false);
    }

    private void givenCandles(List<Candle> bars) {
        when(marketData.getDailyCandles(anyString(), anyInt()))
                .thenReturn(new Candles("TEST", bars));
    }

    // -------------------------------------------------------------------------- the happy path

    @Test
    @DisplayName("a clean candidate gets levels, a ratio, a share count and a verdict")
    void analysesACandidateEndToEnd() {
        givenCandles(oscillating(20));

        CandidateAnalysis result = service().analyse(stock("TEST", "50.00", true, "3000000"));

        assertThat(result.verdict()).isIn(CandidateVerdict.PASS, CandidateVerdict.FAIL);
        assertThat(result.stop()).isNotNull().isLessThan(result.entry());
        assertThat(result.target()).isNotNull().isGreaterThan(result.entry());
        assertThat(result.ratio()).isNotNull();
        assertThat(result.shares()).isNotNull();
        assertThat(result.confidence().total()).isEqualTo(6);
    }

    @Test
    @DisplayName("a PASS still reports its share count — the arithmetic is exact regardless of confidence")
    void passAlwaysCarriesAShareCount() {
        givenCandles(oscillating(20));

        CandidateAnalysis result = service().analyse(stock("TEST", "50.00", true, "3000000"));

        if (result.verdict() == CandidateVerdict.PASS) {
            assertThat(result.shares()).isPositive();
        }
        // Whatever the grade, the count is present once levels exist.
        assertThat(result.shares()).isNotNull();
    }

    // ------------------------------------------------------------------------------- refusals

    @Test
    @DisplayName("no levels means NEEDS_LEVELS, with the reason and what to supply — never a guess")
    void refusedLevelsKeepTheRowAndSayWhatIsNeeded() {
        // Only 30 bars: below the 60-bar floor, so the engine refuses outright.
        givenCandles(oscillating(3).subList(0, 30));

        CandidateAnalysis result = service().analyse(stock("TEST", "50.00", true, "3000000"));

        assertThat(result.verdict()).isEqualTo(CandidateVerdict.NEEDS_LEVELS);
        assertThat(result.analysis()).isNull();
        assertThat(result.shares()).isNull();
        assertThat(result.needed()).isNotEmpty();
        assertThat(result.needed().toString())
                .contains("stop:")
                .contains("bars of history");
    }

    @Test
    @DisplayName("a provider failure degrades the row rather than killing the scan")
    void providerFailureIsContained() {
        when(marketData.getDailyCandles(anyString(), anyInt()))
                .thenThrow(new UnknownSymbolException("twelvedata", "TEST"));

        CandidateAnalysis result = service().analyse(stock("TEST", "50.00", true, "3000000"));

        assertThat(result.verdict()).isEqualTo(CandidateVerdict.NEEDS_LEVELS);
        assertThat(result.needed().toString()).contains("market data unavailable");
    }

    @Test
    void aMissingPriceIsReportedRatherThanAssumed() {
        givenCandles(oscillating(20));

        CandidateAnalysis result = service().analyse(stock("TEST", null, true, "3000000"));

        assertThat(result.verdict()).isEqualTo(CandidateVerdict.NEEDS_LEVELS);
        assertThat(result.needed().toString()).contains("set the entry yourself");
    }

    // ----------------------------------------------------------------------------- confidence

    @Test
    @DisplayName("confidence reports six factors, each with the value behind it")
    void confidenceExposesItsInputs() {
        givenCandles(oscillating(20));

        AnalysisConfidence confidence =
                service().analyse(stock("TEST", "50.00", true, "3000000")).confidence();

        assertThat(confidence.factors()).extracting(AnalysisConfidence.Factor::name)
                .containsExactly("Data depth", "Level derivation", "Zone strength",
                        "Data completeness", "Ratio margin", "Sizing headroom");
        assertThat(confidence.factors()).allSatisfy(f ->
                assertThat(f.detail()).isNotBlank());
    }

    @Test
    @DisplayName("thin history and provider gaps drag the grade down, and say why")
    void weakInputsLowerTheGrade() {
        givenCandles(oscillating(20));

        // No EMA200 verdict and no market cap: two factors fail immediately.
        AnalysisConfidence weak =
                service().analyse(stock("TEST", "50.00", null, null)).confidence();
        AnalysisConfidence strong =
                service().analyse(stock("TEST", "50.00", true, "3000000")).confidence();

        assertThat(weak.met()).isLessThan(strong.met());
        assertThat(weak.weaknesses()).extracting(AnalysisConfidence.Factor::name)
                .contains("Data depth", "Data completeness");
    }

    @Test
    @DisplayName("grade boundaries: 5+ is HIGH, 3-4 MEDIUM, else LOW")
    void gradeBoundaries() {
        assertThat(AnalysisConfidence.of(factors(6)).grade()).isEqualTo(AnalysisConfidence.Grade.HIGH);
        assertThat(AnalysisConfidence.of(factors(5)).grade()).isEqualTo(AnalysisConfidence.Grade.HIGH);
        assertThat(AnalysisConfidence.of(factors(4)).grade()).isEqualTo(AnalysisConfidence.Grade.MEDIUM);
        assertThat(AnalysisConfidence.of(factors(3)).grade()).isEqualTo(AnalysisConfidence.Grade.MEDIUM);
        assertThat(AnalysisConfidence.of(factors(2)).grade()).isEqualTo(AnalysisConfidence.Grade.LOW);
        assertThat(AnalysisConfidence.of(factors(0)).grade()).isEqualTo(AnalysisConfidence.Grade.LOW);
    }

    private static List<AnalysisConfidence.Factor> factors(int met) {
        List<AnalysisConfidence.Factor> list = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            list.add(new AnalysisConfidence.Factor("f" + i, i < met, "detail"));
        }
        return list;
    }

    @Test
    @DisplayName("confidence cannot express a probability — no field for one exists, by design")
    void confidenceHasNoProbabilityField() {
        // The whole point of this type is that it describes the derivation, never the outcome.
        // A future edit adding a "winRate" or "probability" field would silently turn a statement
        // about inputs into a forecast, so the shape itself is asserted.
        List<String> names = new ArrayList<>();
        for (RecordComponent c : AnalysisConfidence.class.getRecordComponents()) {
            names.add(c.getName().toLowerCase());
        }
        for (RecordComponent c : AnalysisConfidence.Factor.class.getRecordComponents()) {
            names.add(c.getName().toLowerCase());
        }

        assertThat(names).noneSatisfy(n -> assertThat(n)
                .containsAnyOf("probability", "odds", "winrate", "likelihood", "expectancy",
                        "chance", "success"));
        assertThat(names).containsExactlyInAnyOrder(
                "grade", "met", "factors", "name", "met", "detail");
    }

    @Test
    @DisplayName("a fallback level is visible on the candidate and costs the derivation factor")
    void fallbackLevelsAreFlagged() {
        // A relentless uptrend: no swing low, so the stop comes from the ATR fallback.
        List<Candle> rising = new ArrayList<>();
        LocalDate date = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < 120; i++) {
            double p = 20 + i;
            rising.add(bar(date.plusDays(i), p - 0.5, p + 0.5, p));
        }
        givenCandles(rising);

        CandidateAnalysis result = service().analyse(stock("TEST", "140.00", true, "3000000"));

        assertThat(result.usesFallbackLevel()).isTrue();
        assertThat(result.confidence().weaknesses())
                .extracting(AnalysisConfidence.Factor::name)
                .contains("Level derivation");
    }
}
