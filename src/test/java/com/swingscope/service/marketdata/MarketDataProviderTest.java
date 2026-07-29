package com.swingscope.service.marketdata;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The interface's own contract: a provider that declares nothing must refuse everything with a
 * clear message rather than returning null or throwing something opaque.
 */
class MarketDataProviderTest {

    /** Declares no capabilities at all, so every default method should refuse. */
    private static class BareProvider implements MarketDataProvider {
        private final boolean available;

        BareProvider(boolean available) {
            this.available = available;
        }

        @Override
        public String name() {
            return "bare";
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of();
        }

        @Override
        public boolean isAvailable() {
            return available;
        }
    }

    private final MarketDataProvider provider = new BareProvider(true);

    @Test
    void everyUnimplementedCapabilityRefusesByName() {
        assertRefuses(() -> provider.getQuote("AAPL"), "QUOTE");
        assertRefuses(() -> provider.getDailyCandles("AAPL", 250), "DAILY_CANDLES");
        assertRefuses(() -> provider.search("apple"), "SYMBOL_SEARCH");
        assertRefuses(() -> provider.getEarnings("AAPL", LocalDate.now(), LocalDate.now()), "EARNINGS");
        assertRefuses(provider::getMarketStatus, "MARKET_STATUS");
        assertRefuses(() -> provider.getCompanyProfile("AAPL"), "COMPANY_PROFILE");
    }

    private static void assertRefuses(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                      String capability) {
        assertThatThrownBy(call)
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("bare does not provide " + capability);
    }

    @Test
    void supportsRequiresBothAvailabilityAndTheCapability() {
        MarketDataProvider offline = new BareProvider(false);

        assertThat(provider.supports(MarketDataProvider.Capability.QUOTE)).isFalse();  // not declared
        assertThat(offline.supports(MarketDataProvider.Capability.QUOTE)).isFalse();   // and offline
        assertThat(provider.capabilities()).isEmpty();
    }

    @Test
    void everyCapabilityIsCoveredByTheEnum() {
        assertThat(MarketDataProvider.Capability.values()).containsExactlyInAnyOrder(
                MarketDataProvider.Capability.QUOTE,
                MarketDataProvider.Capability.DAILY_CANDLES,
                MarketDataProvider.Capability.SYMBOL_SEARCH,
                MarketDataProvider.Capability.EARNINGS,
                MarketDataProvider.Capability.MARKET_STATUS,
                MarketDataProvider.Capability.COMPANY_PROFILE);
        assertThat(MarketDataProvider.Capability.valueOf("QUOTE"))
                .isEqualTo(MarketDataProvider.Capability.QUOTE);
    }
}
