package com.swingscope.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Every threshold behind a suggested level, in one place.
 *
 * <p>These are <strong>guesses until Phase 6A measures them</strong>. The backtest harness exists to
 * replace each default with a number that earned its place; until then, treat any suggestion built
 * on them as a starting point, not an answer.
 *
 * @param pivotStrength           bars either side that must be higher/lower to confirm a pivot
 * @param atrPeriod               lookback for Average True Range
 * @param zoneToleranceAtrMultiple pivots within this × ATR of each other collapse into one zone
 * @param stopBufferAtrMultiple   how far below support the stop sits, so noise doesn't trigger it
 * @param minTouches              pivots a zone needs before it counts as a level at all
 * @param maxStopPercent          refuse a stop further than this % from entry — too wide to size
 * @param minBarsForSuggestion    below this much history, refuse rather than guess
 * @param lookbackBars            how many recent bars to consider; older structure goes stale
 * @param fallbackToAtr           when no structure is found, propose a volatility-derived level
 *                                rather than nothing. Adopted 2026-08-02 after the 6A.8 wide run
 *                                found structure no better than {@code entry − 2×ATR} on a matched
 *                                comparison — but highly variable by symbol, so structure is still
 *                                preferred where it exists
 * @param fallbackStopAtrMultiple how far below entry the fallback stop sits
 * @param fallbackRewardMultiple  fallback target as a multiple of the risk distance
 */
@ConfigurationProperties(prefix = "levels")
public record LevelProperties(
        Integer pivotStrength,
        Integer atrPeriod,
        BigDecimal zoneToleranceAtrMultiple,
        BigDecimal stopBufferAtrMultiple,
        Integer minTouches,
        BigDecimal maxStopPercent,
        Integer minBarsForSuggestion,
        Integer lookbackBars,
        Boolean fallbackToAtr,
        BigDecimal fallbackStopAtrMultiple,
        BigDecimal fallbackRewardMultiple
) {

    public LevelProperties {
        if (pivotStrength == null) {
            pivotStrength = 3;
        }
        if (atrPeriod == null) {
            atrPeriod = 14;
        }
        if (zoneToleranceAtrMultiple == null) {
            zoneToleranceAtrMultiple = new BigDecimal("0.5");
        }
        if (stopBufferAtrMultiple == null) {
            stopBufferAtrMultiple = new BigDecimal("0.5");
        }
        if (minTouches == null) {
            minTouches = 2;
        }
        if (maxStopPercent == null) {
            maxStopPercent = new BigDecimal("15");
        }
        if (minBarsForSuggestion == null) {
            minBarsForSuggestion = 60;
        }
        if (lookbackBars == null) {
            lookbackBars = 250;
        }
        if (fallbackToAtr == null) {
            fallbackToAtr = true;
        }
        if (fallbackStopAtrMultiple == null) {
            fallbackStopAtrMultiple = new BigDecimal("2");
        }
        if (fallbackRewardMultiple == null) {
            fallbackRewardMultiple = new BigDecimal("2");
        }
    }
}
