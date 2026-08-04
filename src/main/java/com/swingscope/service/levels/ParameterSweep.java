package com.swingscope.service.levels;

import com.swingscope.config.LevelProperties;
import com.swingscope.domain.backtest.BacktestSettings;
import com.swingscope.domain.backtest.SplitBacktestReport;
import com.swingscope.domain.marketdata.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Runs the level engine over history at many parameter settings and ranks them — the task that turns
 * Phase 6's nine guessed thresholds into measured ones.
 *
 * <h2>Rules this class is built around</h2>
 * <ol>
 *   <li><strong>Ranked on out-of-sample expectancy only.</strong> In-sample results are reported for
 *       contrast, never for ranking. A set that wins on the data it was tuned on has demonstrated
 *       nothing.</li>
 *   <li><strong>The naive baseline is always included.</strong> Per the Phase 6A success criteria, a
 *       structure-based set is adopted only if it beats {@code stop = entry − 2×ATR} out-of-sample.
 *       If none does, <em>that is the finding</em> — ship the baseline or ship nothing.</li>
 *   <li><strong>Thin results are marked, not hidden.</strong> Every row carries its non-overlapping
 *       sample estimate, and {@link Result#conclusive} is false when the held-back half is too small
 *       to support a conclusion however good the number looks.</li>
 * </ol>
 *
 * <p>Pure with respect to market data: it takes candles and returns a ranking. Fetching is the
 * caller's problem, which keeps it testable offline.
 */
@Service
public class ParameterSweep {

    private static final Logger log = LoggerFactory.getLogger(ParameterSweep.class);

    /** Below this many non-overlapping out-of-sample trades, a row is reported but not trusted. */
    public static final int MIN_CONCLUSIVE_TRADES = 20;

    private static final BigDecimal DEFAULT_IN_SAMPLE_FRACTION = new BigDecimal("0.70");

    private final AtrCalculator atrCalculator;
    private final SwingPointDetector detector;

    public ParameterSweep(AtrCalculator atrCalculator, SwingPointDetector detector) {
        this.atrCalculator = atrCalculator;
        this.detector = detector;
    }

    /** The grid to search. Kept small — every extra axis multiplies the overfitting risk. */
    public record Grid(
            List<Integer> pivotStrengths,
            List<BigDecimal> stopBufferAtrMultiples,
            List<Integer> minTouches
    ) {
        public static Grid defaults() {
            return new Grid(
                    List.of(2, 3, 4),
                    List.of(new BigDecimal("0.25"), new BigDecimal("0.5"), new BigDecimal("1.0")),
                    List.of(2, 3));
        }

        public int size() {
            return pivotStrengths.size() * stopBufferAtrMultiples.size() * minTouches.size();
        }
    }

    /**
     * One parameter set's verdict.
     *
     * @param beatsBaseline out-of-sample expectancy above the naive ATR baseline's
     * @param conclusive    enough held-back trades to mean anything
     */
    public record Result(
            String label,
            Integer pivotStrength,
            BigDecimal stopBufferAtrMultiple,
            Integer minTouches,
            SplitBacktestReport report,
            boolean baseline,
            boolean beatsBaseline,
            boolean conclusive,
            /**
             * The number this row is ranked on. For a single symbol it is that symbol's
             * out-of-sample expectancy; for an aggregate it is the mean across symbols. Held
             * explicitly because an aggregate row keeps one symbol's report for detail, and ranking
             * on that report would silently rank the whole table by whichever symbol came first.
             */
            BigDecimal score,
            int symbolCount,
            /**
             * The baseline's expectancy over <strong>only the bars this set also proposed on</strong>.
             *
             * <p>The plain baseline proposes on nearly every bar while a structure set refuses most
             * of them, so comparing their headline numbers compares different trade populations —
             * it may simply be measuring which bars each chose to skip. This is the like-for-like
             * figure, and it is what {@code beatsBaseline} is judged against.
             */
            BigDecimal matchedBaselineR
    ) {
        public BigDecimal outOfSampleExpectancy() {
            return score;
        }

        /** The detail report's own figure — one symbol's, even on an aggregate row. */
        public BigDecimal reportedOutOfSampleExpectancy() {
            return report.outOfSample().expectancyR();
        }

        public BigDecimal inSampleExpectancy() {
            return report.inSample().expectancyR();
        }

        /** Adoptable only if it clears all three gates at once. */
        public boolean adoptable() {
            return !baseline && conclusive && beatsBaseline
                    && outOfSampleExpectancy().signum() > 0;
        }
    }

    public List<Result> sweep(String symbol, List<Candle> bars) {
        return sweep(symbol, bars, Grid.defaults(), BacktestSettings.defaults(),
                DEFAULT_IN_SAMPLE_FRACTION);
    }

    /**
     * @param bars full history for one symbol, oldest first
     * @return every combination plus the baseline, best out-of-sample expectancy first
     */
    public List<Result> sweep(String symbol, List<Candle> bars, Grid grid,
                              BacktestSettings settings, BigDecimal inSampleFraction) {
        log.info("Sweeping {} parameter set(s) for {} over {} bars (in-sample {}%)",
                grid.size(), symbol, bars == null ? 0 : bars.size(),
                inSampleFraction.multiply(BigDecimal.valueOf(100)));

        // The bar every structure-based set has to clear.
        LevelProposer naive = new NaiveAtrProposer(atrCalculator);
        SplitBacktestReport baselineReport = newBacktest(defaultProperties())
                .replaySplit(symbol, bars, settings, naive, inSampleFraction);
        BigDecimal baselineExpectancy = baselineReport.outOfSample().expectancyR();

        List<Result> results = new ArrayList<>();
        results.add(new Result("baseline: stop = entry − 2×ATR", null, null, null,
                baselineReport, true, false,
                baselineReport.isConclusive(MIN_CONCLUSIVE_TRADES),
                baselineExpectancy, 1, baselineExpectancy));

        for (Integer strength : grid.pivotStrengths()) {
            for (BigDecimal buffer : grid.stopBufferAtrMultiples()) {
                for (Integer touches : grid.minTouches()) {
                    LevelProperties properties = propertiesFor(strength, buffer, touches);
                    LevelBacktestService backtest = newBacktest(properties);

                    SplitBacktestReport report = backtest.replaySplit(symbol, bars, settings,
                            backtest.structureProposer(), inSampleFraction);

                    BigDecimal outOfSample = report.outOfSample().expectancyR();

                    // Like-for-like: run the baseline over ONLY the bars this set proposed on.
                    java.util.Set<Integer> proposedOn = report.outOfSample().trades().stream()
                            .filter(t -> t.outcome() != com.swingscope.domain.backtest
                                    .BacktestOutcome.NO_SUGGESTION)
                            .map(com.swingscope.domain.backtest.BacktestTrade::entryBarIndex)
                            .collect(java.util.stream.Collectors.toSet());
                    BigDecimal matched = proposedOn.isEmpty() ? baselineExpectancy
                            : backtest.replaySplit(symbol, bars, settings,
                                    restrictedTo(naive, proposedOn), inSampleFraction)
                                    .outOfSample().expectancyR();

                    results.add(new Result(
                            "pivot=%d buffer=%s×ATR touches=%d".formatted(strength, buffer, touches),
                            strength, buffer, touches, report, false,
                            outOfSample.compareTo(matched) > 0,
                            report.isConclusive(MIN_CONCLUSIVE_TRADES),
                            outOfSample, 1, matched));
                }
            }
        }

        results.sort(Comparator.comparing(Result::outOfSampleExpectancy).reversed());

        long adoptable = results.stream().filter(Result::adoptable).count();
        log.info("Sweep of {} complete: baseline {}R out-of-sample; {} of {} set(s) adoptable{}",
                symbol, baselineExpectancy, adoptable, grid.size(),
                adoptable == 0 ? " — NOTHING BEAT THE BASELINE, which is itself the finding" : "");
        return List.copyOf(results);
    }

    /**
     * Aggregates a sweep across several symbols by averaging each set's out-of-sample expectancy.
     * One symbol's sweep is far too thin to tune on; this is the number worth looking at.
     */
    public List<Result> sweepAcross(Map<String, List<Candle>> barsBySymbol, Grid grid,
                                    BacktestSettings settings, BigDecimal inSampleFraction) {
        Map<String, List<Result>> perSymbol = new java.util.LinkedHashMap<>();
        barsBySymbol.forEach((symbol, bars) ->
                perSymbol.put(symbol, sweep(symbol, bars, grid, settings, inSampleFraction)));

        Map<String, List<Result>> byLabel = new java.util.LinkedHashMap<>();
        perSymbol.values().forEach(list ->
                list.forEach(r -> byLabel.computeIfAbsent(r.label(), k -> new ArrayList<>()).add(r)));

        List<Result> aggregated = new ArrayList<>();
        byLabel.forEach((label, rows) -> aggregated.add(average(label, rows)));

        // Recompute "beats baseline" against the aggregated baseline mean, not per-symbol verdicts.
        BigDecimal baselineMean = aggregated.stream().filter(Result::baseline)
                .map(Result::outOfSampleExpectancy).findFirst().orElse(BigDecimal.ZERO);
        List<Result> scored = aggregated.stream()
                .map(r -> new Result(r.label(), r.pivotStrength(), r.stopBufferAtrMultiple(),
                        r.minTouches(), r.report(), r.baseline(),
                        !r.baseline()
                                && r.outOfSampleExpectancy().compareTo(r.matchedBaselineR()) > 0,
                        r.conclusive(), r.outOfSampleExpectancy(), r.symbolCount(),
                        r.matchedBaselineR()))
                .sorted(Comparator.comparing(Result::outOfSampleExpectancy).reversed())
                .toList();
        return List.copyOf(scored);
    }

    /** Averages a set's results across symbols, keeping the strictest view of conclusiveness. */
    private static Result average(String label, List<Result> rows) {
        Result first = rows.get(0);
        BigDecimal totalOut = rows.stream()
                .map(Result::outOfSampleExpectancy)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal meanOut = totalOut.divide(BigDecimal.valueOf(rows.size()), 2,
                java.math.RoundingMode.HALF_UP);

        int totalNonOverlapping = rows.stream()
                .mapToInt(r -> r.report().outOfSample().nonOverlappingEstimate()).sum();

        // Represented by the first symbol's report, with the averaged score carried in the label.
        BigDecimal meanMatched = rows.stream().map(Result::matchedBaselineR)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(rows.size()), 2, java.math.RoundingMode.HALF_UP);

        return new Result(label,
                first.pivotStrength(), first.stopBufferAtrMultiple(), first.minTouches(),
                first.report(), first.baseline(),
                meanOut.compareTo(meanMatched) > 0,
                totalNonOverlapping >= MIN_CONCLUSIVE_TRADES,
                meanOut, rows.size(), meanMatched);
    }

    /** Wraps a proposer so it answers only on the given entry bars — the like-for-like device. */
    private static LevelProposer restrictedTo(LevelProposer delegate, java.util.Set<Integer> bars) {
        return new LevelProposer() {
            @Override
            public String name() {
                return delegate.name() + " (matched)";
            }

            @Override
            public java.util.Optional<ProposedLevels> propose(String symbol, List<Candle> asOf,
                                                             BigDecimal price) {
                // The entry bar is the last one visible, so its index is the list's last position.
                return bars.contains(asOf.size() - 1)
                        ? delegate.propose(symbol, asOf, price)
                        : java.util.Optional.empty();
            }
        };
    }

    private LevelBacktestService newBacktest(LevelProperties properties) {
        PriceLevelService levels = new PriceLevelService(detector, atrCalculator, properties);
        LevelSuggestionService suggestions =
                new LevelSuggestionService(null, levels, atrCalculator, properties);
        return new LevelBacktestService(suggestions, properties);
    }

    private static LevelProperties defaultProperties() {
        return new LevelProperties(null, null, null, null, null, null, null, null, null, null, null);
    }

    private static LevelProperties propertiesFor(int pivotStrength, BigDecimal stopBuffer,
                                                 int minTouches) {
        LevelProperties d = defaultProperties();
        // Fallback OFF during a sweep: we are measuring the STRUCTURE method, and letting it fall
        // back to ATR would quietly blend the baseline into every set and flatter the comparison.
        return new LevelProperties(pivotStrength, d.atrPeriod(), d.zoneToleranceAtrMultiple(),
                stopBuffer, minTouches, d.maxStopPercent(), d.minBarsForSuggestion(),
                d.lookbackBars(), false, d.fallbackStopAtrMultiple(), d.fallbackRewardMultiple());
    }
}
