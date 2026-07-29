package com.swingscope.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataPropertiesTest {

    @Test
    void fillsInDefaultsWhenNothingIsConfigured() {
        MarketDataProperties properties = new MarketDataProperties(null, null, null);

        assertThat(properties.twelvedata().retries()).isEqualTo(2);
        assertThat(properties.twelvedata().retryBackoff()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.finnhub().retries()).isEqualTo(2);
        assertThat(properties.ttl().quote()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.ttl().candles()).isEqualTo(Duration.ofHours(6));
        assertThat(properties.ttl().earnings()).isEqualTo(Duration.ofHours(12));
        assertThat(properties.ttl().profile()).isEqualTo(Duration.ofDays(1));
        assertThat(properties.ttl().search()).isEqualTo(Duration.ofDays(1));
        assertThat(properties.ttl().marketStatus()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void keepsConfiguredValues() {
        MarketDataProperties properties = new MarketDataProperties(
                new MarketDataProperties.Provider("https://api.twelvedata.com", "key", true, 5,
                        Duration.ofSeconds(2)),
                null,
                new MarketDataProperties.Ttl(Duration.ofMinutes(1), Duration.ofHours(2),
                        Duration.ofHours(3), Duration.ofHours(4), Duration.ofHours(5),
                        Duration.ofHours(6), Duration.ofHours(7)));

        assertThat(properties.twelvedata().retries()).isEqualTo(5);
        assertThat(properties.twelvedata().retryBackoff()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.ttl().quote()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.ttl().news()).isEqualTo(Duration.ofHours(6));
        assertThat(properties.ttl().marketStatus()).isEqualTo(Duration.ofHours(7));
    }

    @Test
    void aProviderIsUsableOnlyWhenEnabledWithAKeyAndAUrl() {
        assertThat(usable("https://x", "key", true)).isTrue();
        assertThat(usable("https://x", "key", false)).isFalse();     // switched off
        assertThat(usable("https://x", "   ", true)).isFalse();      // blank key
        assertThat(usable("https://x", null, true)).isFalse();       // no key at all
        assertThat(usable(null, "key", true)).isFalse();             // no base url
        assertThat(usable("  ", "key", true)).isFalse();
    }

    private static boolean usable(String baseUrl, String apiKey, boolean enabled) {
        return new MarketDataProperties.Provider(baseUrl, apiKey, enabled, 2, Duration.ZERO).isUsable();
    }

    @Test
    void aNonPositiveRetryCountFallsBackToTheDefault() {
        assertThat(new MarketDataProperties.Provider("u", "k", true, 0, null).retries()).isEqualTo(2);
        assertThat(new MarketDataProperties.Provider("u", "k", true, -1, null).retries()).isEqualTo(2);
    }
}
