package com.swingscope.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * The human-supplied trade plan. The tool never invents these numbers — the user reads the chart,
 * picks the trigger candle, and types entry/stop/target here.
 *
 * @param riskPct percent form: {@code 1.0} means 1% of the account.
 */
public record TradeSetup(
        @NotBlank String ticker,
        @NotNull @Positive BigDecimal entry,
        @NotNull @Positive BigDecimal stop,
        @NotNull @Positive BigDecimal target,
        @NotNull @Positive BigDecimal accountSize,
        @NotNull @Positive BigDecimal riskPct
) {
}
