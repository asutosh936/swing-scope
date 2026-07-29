package com.swingscope.web;

import com.swingscope.domain.TradeSetup;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TradeSetupFormTest {

    @Test
    void toSetupNormalisesTheTickerAndCarriesEveryNumberThrough() {
        TradeSetupForm form = new TradeSetupForm();
        form.setTicker("  vz  ");
        form.setEntry(new BigDecimal("40.00"));
        form.setStop(new BigDecimal("39.00"));
        form.setTarget(new BigDecimal("43.60"));
        form.setAccountSize(new BigDecimal("500"));
        form.setRiskAmount(new BigDecimal("5.00"));

        TradeSetup setup = form.toSetup();

        assertThat(setup.ticker()).isEqualTo("VZ");
        assertThat(setup.entry()).isEqualByComparingTo("40.00");
        assertThat(setup.stop()).isEqualByComparingTo("39.00");
        assertThat(setup.target()).isEqualByComparingTo("43.60");
        assertThat(setup.accountSize()).isEqualByComparingTo("500");
        assertThat(setup.riskAmount()).isEqualByComparingTo("5.00");
    }

    @Test
    void gettersReturnWhatTheSettersStored() {
        TradeSetupForm form = new TradeSetupForm();
        form.setTicker("CARR");
        form.setEntry(new BigDecimal("15.50"));
        form.setStop(new BigDecimal("14.75"));
        form.setTarget(new BigDecimal("18.50"));
        form.setAccountSize(new BigDecimal("1000"));
        form.setRiskAmount(new BigDecimal("20.00"));

        assertThat(form.getTicker()).isEqualTo("CARR");
        assertThat(form.getEntry()).isEqualByComparingTo("15.50");
        assertThat(form.getStop()).isEqualByComparingTo("14.75");
        assertThat(form.getTarget()).isEqualByComparingTo("18.50");
        assertThat(form.getAccountSize()).isEqualByComparingTo("1000");
        assertThat(form.getRiskAmount()).isEqualByComparingTo("20.00");
    }
}
