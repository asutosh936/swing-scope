package com.swingscope.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Domain rules from the plan, kept in config so they are tunable without touching the math.
 *
 * @param minRiskReward     minimum reward:risk a setup must clear to PASS
 * @param defaultAccountSize UI/API default account size
 * @param defaultRiskPct    UI/API default risk per trade, percent form (1.0 = 1%)
 */
@ConfigurationProperties(prefix = "trading.rules")
public record TradingRules(
        BigDecimal minRiskReward,
        BigDecimal defaultAccountSize,
        BigDecimal defaultRiskPct
) {

    public TradingRules {
        if (minRiskReward == null) {
            minRiskReward = new BigDecimal("2.0");
        }
        if (defaultAccountSize == null) {
            defaultAccountSize = new BigDecimal("500");
        }
        if (defaultRiskPct == null) {
            defaultRiskPct = BigDecimal.ONE;
        }
    }
}
