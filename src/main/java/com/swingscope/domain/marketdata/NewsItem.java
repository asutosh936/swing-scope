package com.swingscope.domain.marketdata;

import java.time.Instant;

/**
 * One company news story. Context only — Phase 5's AI summary explains <em>why</em> a stock moved
 * and is explicitly never a trade signal.
 *
 * @param publishedAt when the story ran; Finnhub reports this as an epoch-second timestamp
 */
public record NewsItem(
        String symbol,
        String headline,
        String summary,
        String source,
        String url,
        String category,
        Instant publishedAt
) {
}
