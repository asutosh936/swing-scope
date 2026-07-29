package com.swingscope.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import static org.assertj.core.api.Assertions.assertThat;

class CacheConfigTest {

    private final CacheManager cacheManager =
            new CacheConfig().cacheManager(new MarketDataProperties(null, null, null));

    @Test
    void definesACacheForEveryProviderCall() {
        assertThat(cacheManager.getCacheNames()).containsExactlyInAnyOrder(
                CacheConfig.QUOTES, CacheConfig.CANDLES, CacheConfig.EARNINGS,
                CacheConfig.PROFILE, CacheConfig.SEARCH, CacheConfig.NEWS,
                CacheConfig.MARKET_STATUS);
    }

    @Test
    void cachesStoreAndReturnValues() {
        Cache quotes = cacheManager.getCache(CacheConfig.QUOTES);

        assertThat(quotes).isNotNull();
        assertThat(quotes.get("AAPL")).isNull();

        quotes.put("AAPL", "cached-quote");
        assertThat(quotes.get("AAPL").get()).isEqualTo("cached-quote");

        quotes.evict("AAPL");
        assertThat(quotes.get("AAPL")).isNull();
    }
}
