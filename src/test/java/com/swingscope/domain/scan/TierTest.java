package com.swingscope.domain.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TierTest {

    @Test
    @DisplayName("only Tier 1 and Tier 2 are worth planning a trade against")
    void onlyTheTopTwoTiersAreTradeable() {
        assertThat(Tier.TIER1.isTradeable()).isTrue();
        assertThat(Tier.TIER2.isTradeable()).isTrue();
        assertThat(Tier.TIER3.isTradeable()).isFalse();
        assertThat(Tier.SKIP.isTradeable()).isFalse();
        assertThat(Tier.UNAVAILABLE.isTradeable()).isFalse();
    }

    @Test
    void everyTierExplainsItself() {
        for (Tier tier : Tier.values()) {
            assertThat(tier.getLabel()).isNotBlank();
            assertThat(tier.getDescription()).isNotBlank();
        }
    }

    @Test
    @DisplayName("tier order drives the sort — best first, unavailable last")
    void ordinalOrderIsTheDisplayOrder() {
        assertThat(Tier.TIER1.ordinal()).isLessThan(Tier.TIER2.ordinal());
        assertThat(Tier.TIER2.ordinal()).isLessThan(Tier.TIER3.ordinal());
        assertThat(Tier.TIER3.ordinal()).isLessThan(Tier.SKIP.ordinal());
        assertThat(Tier.SKIP.ordinal()).isLessThan(Tier.UNAVAILABLE.ordinal());
    }
}
