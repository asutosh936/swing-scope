package com.swingscope.domain.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One replayed setup, resolved.
 *
 * <p>Results are in <strong>R</strong> — multiples of the risk distance — because position size
 * varies with price and account. A clean stop is exactly −1R; a target at 2.5× the risk distance is
 * +2.5R; a gap through the stop is worse than −1R, which is precisely the case a dollar-based
 * summary would hide.
 *
 * @param ambiguousBar   the deciding bar's range contained both levels, so daily data cannot say
 *                       which came first. Resolved as a stop. Tracked so the rate is auditable —
 *                       a report where most trades resolved ambiguously is not a measurement
 * @param gapped         the exit filled at a bar's open beyond the level, not at the level itself
 */
public record BacktestTrade(
        String symbol,
        int entryBarIndex,
        LocalDate entryDate,
        BigDecimal entryPrice,
        BigDecimal stop,
        BigDecimal target,
        int exitBarIndex,
        LocalDate exitDate,
        BigDecimal exitPrice,
        BacktestOutcome outcome,
        BigDecimal rMultiple,
        int barsHeld,
        boolean ambiguousBar,
        boolean gapped
) {

    /**
     * A bar where the engine proposed nothing at all. Levels are genuinely absent, not discarded.
     */
    public static BacktestTrade noSuggestion(String symbol, int barIndex, LocalDate date) {
        return new BacktestTrade(symbol, barIndex, date, null, null, null,
                barIndex, date, null, BacktestOutcome.NO_SUGGESTION, null, 0, false, false);
    }

    /**
     * Levels were computed but no R can be scored — the setup could not be entered, or the series
     * ended before it resolved. <strong>The levels are kept</strong>: they were real output, and
     * discarding them would hide what the engine actually proposed at that bar.
     */
    public static BacktestTrade unresolved(String symbol, int barIndex, LocalDate date,
                                           BigDecimal entryPrice, BigDecimal stop, BigDecimal target,
                                           BacktestOutcome outcome) {
        return new BacktestTrade(symbol, barIndex, date, entryPrice, stop, target,
                barIndex, date, null, outcome, null, 0, false, false);
    }

    public boolean isResolved() {
        return outcome.isResolved() && rMultiple != null;
    }
}
