package com.swingscope.domain.journal;

import java.math.BigDecimal;

/**
 * The running scorecard.
 *
 * <p>Only completed trades (wins and losses) are counted. Scratches and no-fills are excluded
 * everywhere — they are not evidence about whether the strategy works.
 *
 * @param rejected           setups the rules turned down and were never taken — discipline, not a result
 * @param winRate            percent, 0–100, at 1 dp
 * @param expectancy         average dollars per completed trade: {@code netPnl ÷ closedCount}
 * @param losersWithRulesFollowed how many losing trades were still taken by the rules — the thing
 *                                that matters more than the loss itself
 * @param graduationTarget   completed trades needed before considering real money
 * @param graduationMet      target reached AND net P&L positive AND every loser followed the rules
 */
public record JournalStats(
        long totalEntries,
        long openTrades,
        long closedCount,
        long wins,
        long losses,
        long scratches,
        long noFills,
        long rejected,
        BigDecimal winRate,
        BigDecimal netPnl,
        BigDecimal expectancy,
        BigDecimal averageWin,
        BigDecimal averageLoss,
        long losersWithRulesFollowed,
        int graduationTarget,
        BigDecimal graduationPercent,
        boolean graduationMet
) {

    public static JournalStats empty(int graduationTarget) {
        return new JournalStats(0, 0, 0, 0, 0, 0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0, graduationTarget, BigDecimal.ZERO, false);
    }
}
