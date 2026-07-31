package com.swingscope.domain.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * Aggregate result of a replay.
 *
 * <h2>Read {@code nonOverlappingEstimate}, not {@code resolvedTrades}</h2>
 * Consecutive entries share almost all their history: the same pivots, the same zones, a stop a few
 * cents apart. Two hundred entries from one symbol-year are not two hundred independent
 * observations — closer to {@code bars ÷ holdingPeriod}. Quoting the raw count is how a backtest
 * flatters itself, so both are reported and the honest one is named.
 *
 * @param expectancyR      mean R across resolved trades — the headline number
 * @param ambiguousRate    share of resolved trades decided by an intrabar ambiguity, all of which
 *                         were scored as losses. A high rate means daily bars were too coarse here
 */
public record BacktestReport(
        String symbol,
        List<BacktestTrade> trades,
        int entriesConsidered,
        int resolvedTrades,
        int nonOverlappingEstimate,
        int targetFirst,
        int stopFirst,
        int timeouts,
        int noSuggestion,
        int notTakeable,
        int incomplete,
        BigDecimal hitRate,
        BigDecimal expectancyR,
        BigDecimal averageWinR,
        BigDecimal averageLossR,
        BigDecimal worstR,
        BigDecimal ambiguousRate,
        int medianBarsHeld
) {

    public BacktestReport {
        trades = trades == null ? List.of() : List.copyOf(trades);
    }

    public static BacktestReport of(String symbol, List<BacktestTrade> all, int timeStopBars) {
        List<BacktestTrade> resolved = all.stream().filter(BacktestTrade::isResolved).toList();

        int targetFirst = count(all, BacktestOutcome.TARGET_FIRST);
        int stopFirst = count(all, BacktestOutcome.STOP_FIRST);
        int timeouts = count(all, BacktestOutcome.TIMEOUT);
        int noSuggestion = count(all, BacktestOutcome.NO_SUGGESTION);
        int notTakeable = count(all, BacktestOutcome.NOT_TAKEABLE);
        int incomplete = count(all, BacktestOutcome.INCOMPLETE);

        BigDecimal hitRate = ratio(targetFirst, resolved.size());
        BigDecimal expectancy = mean(resolved.stream().map(BacktestTrade::rMultiple).toList());

        List<BigDecimal> wins = resolved.stream()
                .map(BacktestTrade::rMultiple).filter(r -> r.signum() > 0).toList();
        List<BigDecimal> losses = resolved.stream()
                .map(BacktestTrade::rMultiple).filter(r -> r.signum() <= 0).toList();

        BigDecimal worst = resolved.stream().map(BacktestTrade::rMultiple)
                .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        long ambiguous = resolved.stream().filter(BacktestTrade::ambiguousBar).count();

        List<Integer> held = resolved.stream().map(BacktestTrade::barsHeld)
                .sorted(Comparator.naturalOrder()).toList();
        int median = held.isEmpty() ? 0 : held.get(held.size() / 2);

        // Entries overlap heavily; one independent observation per holding period is the fair read.
        int independent = timeStopBars <= 0 ? resolved.size() : resolved.size() / timeStopBars;

        return new BacktestReport(symbol, all, all.size(), resolved.size(), independent,
                targetFirst, stopFirst, timeouts, noSuggestion, notTakeable, incomplete,
                hitRate, expectancy, mean(wins), mean(losses), worst,
                ratio((int) ambiguous, resolved.size()), median);
    }

    private static int count(List<BacktestTrade> trades, BacktestOutcome outcome) {
        return (int) trades.stream().filter(t -> t.outcome() == outcome).count();
    }

    private static BigDecimal ratio(int part, int whole) {
        return whole == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }
}
