package com.swingscope.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradeAnalysisTest {

    @Test
    void rejectedZeroesEveryNumberAndCarriesTheReason() {
        TradeAnalysis rejected = TradeAnalysis.rejected("VZ", "stop must be below entry");

        assertThat(rejected.ticker()).isEqualTo("VZ");
        assertThat(rejected.pass()).isFalse();
        assertThat(rejected.reason()).isEqualTo("stop must be below entry");
        assertThat(rejected.wholeShares()).isZero();
        assertThat(rejected.riskPerShare()).isEqualByComparingTo("0");
        assertThat(rejected.rewardPerShare()).isEqualByComparingTo("0");
        assertThat(rejected.ratio()).isEqualByComparingTo("0");
        assertThat(rejected.idealShares()).isEqualByComparingTo("0");
        assertThat(rejected.totalRisk()).isEqualByComparingTo("0");
        assertThat(rejected.positionCost()).isEqualByComparingTo("0");
        assertThat(rejected.cashLeft()).isEqualByComparingTo("0");
    }
}
