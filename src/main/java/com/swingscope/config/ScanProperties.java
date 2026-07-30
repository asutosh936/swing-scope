package com.swingscope.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Thresholds for the Tier 1 / Tier 2 split, plus the scan batch ceiling.
 *
 * <p><strong>Market cap is in millions</strong>, matching what Finnhub reports — {@code 2000} means
 * $2B. Comparing against a dollar figure here would be wrong by a factor of a million.
 *
 * @param tier1MinVolume     average daily volume for Tier 1
 * @param tier1MinMarketCapMillions market cap for Tier 1, in millions
 * @param maxTickersPerScan  guard against pasting a list so long the scan runs for an hour
 */
@ConfigurationProperties(prefix = "scan")
public record ScanProperties(
        Long tier1MinVolume,
        BigDecimal tier1MinMarketCapMillions,
        Integer maxTickersPerScan
) {

    public ScanProperties {
        if (tier1MinVolume == null) {
            tier1MinVolume = 1_000_000L;
        }
        if (tier1MinMarketCapMillions == null) {
            tier1MinMarketCapMillions = new BigDecimal("2000");   // $2B
        }
        if (maxTickersPerScan == null) {
            maxTickersPerScan = 30;
        }
    }
}
