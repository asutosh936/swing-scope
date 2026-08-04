package com.swingscope.service.levels;

import com.swingscope.domain.backtest.BacktestSettings;
import com.swingscope.domain.backtest.SplitBacktestReport;
import com.swingscope.domain.marketdata.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ParameterSweepTest {

    private final AtrCalculator atr = new AtrCalculator();
    private final SwingPointDetector detector = new SwingPointDetector();
    private final ParameterSweep sweep = new ParameterSweep(atr, detector);

    private static Candle bar(LocalDate date, double open, double high, double low, double close) {
        return new Candle(date, BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), 1_000_000L);
    }

    /** Oscillates between a ~40 support shelf and a ~60 resistance shelf. */
    private static List<Candle> oscillating(int cycles) {
        List<Candle> bars = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 1);
        int day = 0;
        for (int c = 0; c < cycles; c++) {
            double[] legDown = {58, 55, 52, 48, 44, 41, 40.2, 41, 44, 48};
            double[] legUp = {52, 55, 58, 59.8, 59, 57, 54, 51, 49, 50};
            for (double p : legDown) {
                bars.add(bar(date.plusDays(day++), p, p + 1, p - 1, p));
            }
            for (double p : legUp) {
                bars.add(bar(date.plusDays(day++), p, p + 1, p - 1, p));
            }
        }
        return bars;
    }

    // ------------------------------------------------------------------------ 6A.6 — the split

    @Test
    @DisplayName("splitting partitions trades by entry bar, and every entry keeps its full history")
    void splitPartitionsByEntryBarNotByTruncatingHistory() {
        List<Candle> bars = oscillating(20);

        SplitBacktestReport report = backtest().replaySplit("TEST", bars,
                BacktestSettings.defaults(), backtest().structureProposer(), new BigDecimal("0.70"));

        assertThat(report.splitBarIndex()).isEqualTo((int) (bars.size() * 0.70));
        assertThat(report.inSample().trades()).isNotEmpty();
        assertThat(report.outOfSample().trades()).isNotEmpty();

        // No trade appears in both halves, and none is lost.
        int total = report.inSample().entriesConsidered() + report.outOfSample().entriesConsidered();
        assertThat(total).isEqualTo(
                backtest().replay("TEST", bars).entriesConsidered());

        report.inSample().trades()
                .forEach(t -> assertThat(t.entryBarIndex()).isLessThan(report.splitBarIndex()));
        report.outOfSample().trades()
                .forEach(t -> assertThat(t.entryBarIndex()).isGreaterThanOrEqualTo(report.splitBarIndex()));
    }

    @Test
    @DisplayName("an out-of-sample entry computes identical levels to the same entry in a full replay")
    void outOfSampleEntriesAreNotHandicapped() {
        List<Candle> bars = oscillating(20);

        SplitBacktestReport split = backtest().replaySplit("TEST", bars,
                BacktestSettings.defaults(), backtest().structureProposer(), new BigDecimal("0.70"));
        var full = backtest().replay("TEST", bars);

        // Splitting the *bars* would starve early out-of-sample entries of lookback. Splitting the
        // trades does not: the same entry must produce the same levels either way.
        split.outOfSample().trades().forEach(outTrade -> {
            var same = full.trades().stream()
                    .filter(t -> t.entryBarIndex() == outTrade.entryBarIndex())
                    .findFirst().orElseThrow();
            assertThat(outTrade.stop()).isEqualTo(same.stop());
            assertThat(outTrade.target()).isEqualTo(same.target());
            assertThat(outTrade.outcome()).isEqualTo(same.outcome());
        });
    }

    @Test
    void degradationIsInSampleMinusOutOfSample() {
        SplitBacktestReport report = backtest().replaySplit("TEST", oscillating(20),
                BacktestSettings.defaults(), backtest().structureProposer(), new BigDecimal("0.70"));

        assertThat(report.degradationR())
                .isEqualByComparingTo(report.inSample().expectancyR()
                        .subtract(report.outOfSample().expectancyR()));
        assertThat(report.rankingScore()).isEqualByComparingTo(report.outOfSample().expectancyR());
    }

    // ------------------------------------------------------------------------ 6A.5 — the sweep

    @Test
    @DisplayName("the sweep covers the whole grid plus the baseline, ranked out-of-sample")
    void sweepsTheGridAndRanksByOutOfSample() {
        ParameterSweep.Grid grid = ParameterSweep.Grid.defaults();

        List<ParameterSweep.Result> results = sweep.sweep("TEST", oscillating(20));

        assertThat(results).hasSize(grid.size() + 1);            // + the baseline row
        assertThat(results).filteredOn(ParameterSweep.Result::baseline).hasSize(1);

        // Sorted by out-of-sample expectancy, descending — never by in-sample.
        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i - 1).outOfSampleExpectancy())
                    .isGreaterThanOrEqualTo(results.get(i).outOfSampleExpectancy());
        }
    }

    @Test
    @DisplayName("the naive baseline is always present — it is the bar a set has to clear")
    void baselineIsAlwaysIncluded() {
        List<ParameterSweep.Result> results = sweep.sweep("TEST", oscillating(20));

        ParameterSweep.Result baseline = results.stream()
                .filter(ParameterSweep.Result::baseline).findFirst().orElseThrow();

        assertThat(baseline.label()).contains("2×ATR");
        assertThat(baseline.pivotStrength()).isNull();
        assertThat(baseline.adoptable())
                .as("the baseline is a benchmark, never itself an adoption candidate")
                .isFalse();
    }

    @Test
    @DisplayName("beatsBaseline is measured against the baseline's OUT-of-sample number")
    void beatsBaselineComparesTheHeldBackHalf() {
        List<ParameterSweep.Result> results = sweep.sweep("TEST", oscillating(20));
        BigDecimal baseline = results.stream().filter(ParameterSweep.Result::baseline)
                .findFirst().orElseThrow().outOfSampleExpectancy();

        results.stream().filter(r -> !r.baseline()).forEach(r ->
                assertThat(r.beatsBaseline())
                        .isEqualTo(r.outOfSampleExpectancy().compareTo(baseline) > 0));
    }

    @Test
    @DisplayName("a thin held-back sample is reported but never called conclusive")
    void thinSamplesAreNotTrusted() {
        // Few bars -> few out-of-sample trades, far below the threshold.
        List<ParameterSweep.Result> results = sweep.sweep("TEST", oscillating(5));

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(r -> {
            assertThat(r.report().outOfSample().nonOverlappingEstimate())
                    .isLessThan(ParameterSweep.MIN_CONCLUSIVE_TRADES);
            assertThat(r.conclusive()).isFalse();
            assertThat(r.adoptable())
                    .as("nothing is adoptable on a sample this thin, whatever the expectancy")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("adoptable requires all three gates: conclusive, beats baseline, positive")
    void adoptableNeedsEveryGate() {
        List<ParameterSweep.Result> results = sweep.sweep("TEST", oscillating(20));

        results.forEach(r -> {
            boolean expected = !r.baseline() && r.conclusive() && r.beatsBaseline()
                    && r.outOfSampleExpectancy().signum() > 0;
            assertThat(r.adoptable()).isEqualTo(expected);
        });
    }

    @Test
    void sweepingAcrossSymbolsAveragesTheHeldBackScore() {
        Map<String, List<Candle>> bySymbol = new LinkedHashMap<>();
        bySymbol.put("AAA", oscillating(20));
        bySymbol.put("BBB", oscillating(18));

        List<ParameterSweep.Result> aggregated = sweep.sweepAcross(bySymbol,
                ParameterSweep.Grid.defaults(), BacktestSettings.defaults(), new BigDecimal("0.70"));

        assertThat(aggregated).hasSize(ParameterSweep.Grid.defaults().size() + 1);
        assertThat(aggregated).allSatisfy(r -> assertThat(r.symbolCount()).isEqualTo(2));

        // The aggregate must score on the MEAN across symbols. Before this was made explicit the
        // score came from whichever symbol's report happened to be kept, which silently ranked the
        // entire table by symbol #1.
        Map<String, BigDecimal> expectedMeans = new LinkedHashMap<>();
        List<ParameterSweep.Result> aaa = sweep.sweep("AAA", bySymbol.get("AAA"),
                ParameterSweep.Grid.defaults(), BacktestSettings.defaults(), new BigDecimal("0.70"));
        List<ParameterSweep.Result> bbb = sweep.sweep("BBB", bySymbol.get("BBB"),
                ParameterSweep.Grid.defaults(), BacktestSettings.defaults(), new BigDecimal("0.70"));
        aaa.forEach(a -> bbb.stream().filter(b -> b.label().equals(a.label())).findFirst()
                .ifPresent(b -> expectedMeans.put(a.label(),
                        a.outOfSampleExpectancy().add(b.outOfSampleExpectancy())
                                .divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP))));

        assertThat(aggregated).allSatisfy(r ->
                assertThat(r.outOfSampleExpectancy())
                        .as("aggregate score for %s must be the mean across symbols", r.label())
                        .isEqualByComparingTo(expectedMeans.get(r.label())));

        for (int i = 1; i < aggregated.size(); i++) {
            assertThat(aggregated.get(i - 1).outOfSampleExpectancy())
                    .isGreaterThanOrEqualTo(aggregated.get(i).outOfSampleExpectancy());
        }
    }

    @Test
    @DisplayName("the naive proposer uses no structure at all — that is what makes it a fair floor")
    void naiveProposerIgnoresStructure() {
        List<Candle> bars = oscillating(20);
        NaiveAtrProposer naive = new NaiveAtrProposer(atr);

        var proposed = naive.propose("TEST", bars, BigDecimal.valueOf(50)).orElseThrow();

        BigDecimal atrValue = atr.atr(bars, AtrCalculator.DEFAULT_PERIOD);
        assertThat(proposed.stop())
                .isEqualByComparingTo(BigDecimal.valueOf(50)
                        .subtract(atrValue.multiply(BigDecimal.valueOf(2)))
                        .setScale(2, java.math.RoundingMode.HALF_UP));
        // Target gives exactly 2R.
        BigDecimal risk = BigDecimal.valueOf(50).subtract(proposed.stop());
        assertThat(proposed.target())
                .isEqualByComparingTo(BigDecimal.valueOf(50).add(risk.multiply(BigDecimal.valueOf(2))));
    }

    @Test
    void naiveProposerDeclinesWithoutEnoughHistory() {
        assertThat(new NaiveAtrProposer(atr).propose("TEST", oscillating(1), BigDecimal.valueOf(50)))
                .isPresent();   // 20 bars is enough for ATR(14)
        assertThat(new NaiveAtrProposer(atr).propose("TEST", List.of(), BigDecimal.valueOf(50)))
                .isEmpty();
        assertThat(new NaiveAtrProposer(atr).propose("TEST", oscillating(20), null))
                .isEmpty();
    }

    // -------------------------------------------------------------------------------- helpers

    private LevelBacktestService backtest() {
        com.swingscope.config.LevelProperties properties =
                new com.swingscope.config.LevelProperties(null, null, null, null, null, null, null, null, false, null, null);
        PriceLevelService levels = new PriceLevelService(detector, atr, properties);
        return new LevelBacktestService(
                new LevelSuggestionService(null, levels, atr, properties), properties);
    }
}
