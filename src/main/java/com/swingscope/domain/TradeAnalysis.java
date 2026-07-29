package com.swingscope.domain;

import java.math.BigDecimal;

/**
 * The result of running a {@link TradeSetup} through the mechanical rules.
 * Never a recommendation — analysis plus PASS/FAIL plus the reason.
 *
 * @param idealShares  unrounded share count implied by the risk budget
 * @param wholeShares  tradeable share count: idealShares floored, then capped by account cash
 * @param totalRisk    wholeShares × riskPerShare — what is actually at risk if the stop fills
 * @param positionCost wholeShares × entry
 * @param cashLeft     accountSize − positionCost
 */
public record TradeAnalysis(
        String ticker,
        BigDecimal riskPerShare,
        BigDecimal rewardPerShare,
        BigDecimal ratio,
        BigDecimal idealShares,
        int wholeShares,
        BigDecimal totalRisk,
        BigDecimal positionCost,
        BigDecimal cashLeft,
        boolean pass,
        String reason
) {

    /** A setup that failed a hard guard before any sizing math was possible. */
    public static TradeAnalysis rejected(String ticker, String reason) {
        return new TradeAnalysis(
                ticker,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                false, reason);
    }
}
