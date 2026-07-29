package com.swingscope.service;

import com.swingscope.config.TradingRules;
import com.swingscope.domain.TradeAnalysis;
import com.swingscope.domain.TradeSetup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TradeCalculatorServiceTest {

    private static final BigDecimal ACCOUNT = new BigDecimal("500");
    private static final BigDecimal RISK_PCT = new BigDecimal("1.0");

    private final TradeCalculatorService calculator =
            new TradeCalculatorService(new TradingRules(null, null, null));

    private static TradeSetup setup(String ticker, String entry, String stop, String target) {
        return new TradeSetup(ticker, new BigDecimal(entry), new BigDecimal(stop),
                new BigDecimal(target), ACCOUNT, RISK_PCT);
    }

    @Test
    @DisplayName("VZ: 1:3.6 reward:risk passes and sizes to the full risk budget")
    void vzPasses() {
        TradeAnalysis result = calculator.analyze(setup("VZ", "40.00", "39.00", "43.60"));

        assertThat(result.riskPerShare()).isEqualByComparingTo("1.00");
        assertThat(result.rewardPerShare()).isEqualByComparingTo("3.60");
        assertThat(result.ratio()).isEqualByComparingTo("3.60");
        assertThat(result.wholeShares()).isEqualTo(5);   // $5 risk budget / $1.00 per share
        assertThat(result.totalRisk()).isEqualByComparingTo("5.00");
        assertThat(result.positionCost()).isEqualByComparingTo("200.00");
        assertThat(result.cashLeft()).isEqualByComparingTo("300.00");
        assertThat(result.pass()).isTrue();
        assertThat(result.reason()).isEqualTo("PASS");
    }

    @Test
    @DisplayName("CI: 1.87 ratio fails the 2.0 minimum even though the size is fine")
    void ciFailsOnRatio() {
        TradeAnalysis result = calculator.analyze(setup("CI", "20.00", "19.00", "21.87"));

        assertThat(result.ratio()).isEqualByComparingTo("1.87");
        assertThat(result.wholeShares()).isEqualTo(5);
        assertThat(result.pass()).isFalse();
        assertThat(result.reason()).isEqualTo("ratio 1.87 < 2.0");
    }

    @Test
    @DisplayName("CARR: fractional ideal share count rounds DOWN to whole shares")
    void carrRoundsDown() {
        TradeAnalysis result = calculator.analyze(setup("CARR", "15.50", "14.75", "18.50"));

        assertThat(result.riskPerShare()).isEqualByComparingTo("0.75");
        assertThat(result.ratio()).isEqualByComparingTo("4.00");
        assertThat(result.idealShares()).isEqualByComparingTo("6.6667");
        assertThat(result.wholeShares()).isEqualTo(6);
        assertThat(result.totalRisk()).isEqualByComparingTo("4.50");   // under budget, never over
        assertThat(result.positionCost()).isEqualByComparingTo("93.00");
        assertThat(result.pass()).isTrue();
    }

    @Test
    @DisplayName("stop at or above entry is rejected outright")
    void stopAboveEntryIsRejected() {
        TradeAnalysis result = calculator.analyze(setup("XYZ", "40.00", "41.00", "45.00"));

        assertThat(result.pass()).isFalse();
        assertThat(result.reason()).isEqualTo("stop must be below entry");
        assertThat(result.wholeShares()).isZero();

        TradeAnalysis equalStop = calculator.analyze(setup("XYZ", "40.00", "40.00", "45.00"));
        assertThat(equalStop.reason()).isEqualTo("stop must be below entry");
    }

    @Test
    @DisplayName("target at or below entry is rejected outright")
    void targetBelowEntryIsRejected() {
        TradeAnalysis result = calculator.analyze(setup("XYZ", "40.00", "39.00", "39.50"));

        assertThat(result.pass()).isFalse();
        assertThat(result.reason()).isEqualTo("target must be above entry");
    }

    @Test
    @DisplayName("a tight stop can afford more shares than the account holds — cash caps the size")
    void cashCapsSizeBelowRiskBudget() {
        TradeAnalysis result = calculator.analyze(setup("MSFT", "100.00", "99.50", "102.00"));

        assertThat(result.ratio()).isEqualByComparingTo("4.00");
        assertThat(result.idealShares()).isEqualByComparingTo("10.0000"); // risk budget allows 10
        assertThat(result.wholeShares()).isEqualTo(5);                    // $500 only buys 5
        assertThat(result.positionCost()).isEqualByComparingTo("500.00");
        assertThat(result.cashLeft()).isEqualByComparingTo("0.00");
        assertThat(result.totalRisk()).isEqualByComparingTo("2.50");
        assertThat(result.pass()).isTrue();
        assertThat(result.reason()).isEqualTo("PASS (size capped by available cash, not by the risk budget)");
    }

    @Test
    @DisplayName("an expensive stock the account cannot afford sizes to zero and fails")
    void unaffordableStockFails() {
        TradeAnalysis result = calculator.analyze(setup("BRK.B", "900.00", "890.00", "950.00"));

        assertThat(result.ratio()).isEqualByComparingTo("5.00");
        assertThat(result.wholeShares()).isZero();
        assertThat(result.positionCost()).isEqualByComparingTo("0.00");
        assertThat(result.pass()).isFalse();
        assertThat(result.reason()).isEqualTo("position size is 0 shares — entry price exceeds the account balance");
    }

    @Test
    @DisplayName("a bad ratio AND a zero size are reported together")
    void badRatioAndZeroSizeAreBothReported() {
        TradeAnalysis result = calculator.analyze(setup("NFLX", "900.00", "890.00", "910.00"));

        assertThat(result.ratio()).isEqualByComparingTo("1.00");
        assertThat(result.wholeShares()).isZero();
        assertThat(result.pass()).isFalse();
        assertThat(result.reason()).isEqualTo("ratio 1.00 < 2.0, and position size is 0 shares");
    }

    @Test
    @DisplayName("a stop wider than the whole risk budget sizes to zero")
    void riskPerShareOverBudgetFails() {
        TradeAnalysis result = calculator.analyze(setup("AAPL", "50.00", "42.00", "70.00"));

        assertThat(result.ratio()).isEqualByComparingTo("2.50");
        assertThat(result.wholeShares()).isZero();
        assertThat(result.pass()).isFalse();
        assertThat(result.reason()).isEqualTo("position size is 0 shares — risk per share exceeds the risk budget");
    }
}
