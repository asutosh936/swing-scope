package com.swingscope.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TradingRulesTest {

    @Test
    void appliesPlanDefaultsWhenNothingIsConfigured() {
        TradingRules rules = new TradingRules(null, null, null);

        assertThat(rules.minRiskReward()).isEqualByComparingTo("2.0");
        assertThat(rules.defaultAccountSize()).isEqualByComparingTo("500");
        assertThat(rules.defaultRiskPct()).isEqualByComparingTo("1");
    }

    @Test
    void keepsConfiguredValues() {
        TradingRules rules = new TradingRules(
                new BigDecimal("3.0"), new BigDecimal("2500"), new BigDecimal("0.5"));

        assertThat(rules.minRiskReward()).isEqualByComparingTo("3.0");
        assertThat(rules.defaultAccountSize()).isEqualByComparingTo("2500");
        assertThat(rules.defaultRiskPct()).isEqualByComparingTo("0.5");
    }
}
