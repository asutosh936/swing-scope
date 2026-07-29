package com.swingscope.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Provider configuration. Keys come from environment variables and are never committed.
 *
 * <p>Twelve Data is the primary source: Finnhub moved historical candles to its premium tier, so a
 * free Finnhub key cannot feed the EMA calculation. Finnhub stays for the endpoints that are still
 * free — earnings, market status, company profile, news.
 */
@ConfigurationProperties(prefix = "marketdata")
public record MarketDataProperties(
        Provider twelvedata,
        Provider finnhub,
        Ttl ttl
) {

    public MarketDataProperties {
        if (twelvedata == null) {
            twelvedata = new Provider(null, null, true, 0, null);
        }
        if (finnhub == null) {
            finnhub = new Provider(null, null, true, 0, null);
        }
        if (ttl == null) {
            ttl = new Ttl(null, null, null, null, null, null, null);
        }
    }

    /**
     * @param apiKey       from an env var; blank disables the provider at startup
     * @param retries      how many times to retry a 429 before giving up
     * @param retryBackoff base delay between 429 retries; doubles each attempt
     */
    public record Provider(
            String baseUrl,
            String apiKey,
            boolean enabled,
            int retries,
            Duration retryBackoff
    ) {
        public Provider {
            if (retries <= 0) {
                retries = 2;
            }
            if (retryBackoff == null) {
                retryBackoff = Duration.ofMillis(500);
            }
        }

        public boolean isUsable() {
            return enabled && apiKey != null && !apiKey.isBlank()
                    && baseUrl != null && !baseUrl.isBlank();
        }
    }

    /** Cache lifetimes, sized against Twelve Data's 800-requests-per-day free tier. */
    public record Ttl(
            Duration quote,
            Duration candles,
            Duration earnings,
            Duration profile,
            Duration search,
            Duration news,
            Duration marketStatus
    ) {
        public Ttl {
            if (quote == null) {
                quote = Duration.ofMinutes(5);
            }
            if (candles == null) {
                candles = Duration.ofHours(6);      // daily bars only change once a day
            }
            if (earnings == null) {
                earnings = Duration.ofHours(12);
            }
            if (profile == null) {
                profile = Duration.ofDays(1);       // market cap barely moves intraday
            }
            if (search == null) {
                search = Duration.ofDays(1);        // ticker listings are effectively static
            }
            if (news == null) {
                news = Duration.ofHours(1);         // fresh enough to explain today's move
            }
            if (marketStatus == null) {
                marketStatus = Duration.ofMinutes(10);
            }
        }
    }
}
