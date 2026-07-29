package com.swingscope.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * Per-cache TTLs. The point is the free-tier budget: Twelve Data allows 800 calls a day and 8 a
 * minute, so scanning a 20-name watchlist repeatedly has to come out of cache, not the wire.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    static final String QUOTES = "quotes";
    static final String CANDLES = "candles";
    static final String EARNINGS = "earnings";
    static final String PROFILE = "profile";
    static final String SEARCH = "search";
    static final String NEWS = "news";
    static final String MARKET_STATUS = "marketStatus";

    @Bean
    public CacheManager cacheManager(MarketDataProperties properties) {
        MarketDataProperties.Ttl ttl = properties.ttl();
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                cache(QUOTES, ttl.quote(), 500),
                cache(CANDLES, ttl.candles(), 200),
                cache(EARNINGS, ttl.earnings(), 500),
                cache(PROFILE, ttl.profile(), 500),
                cache(SEARCH, ttl.search(), 200),
                cache(NEWS, ttl.news(), 200),
                cache(MARKET_STATUS, ttl.marketStatus(), 1)));
        // SimpleCacheManager only publishes its caches once initialised; Spring would call this
        // via InitializingBean, but doing it here keeps the bean usable outside a container too.
        manager.initializeCaches();

        log.info("Market data caches configured — quotes={}, candles={}, earnings={}, profile={}, "
                        + "search={}, news={}, marketStatus={}",
                ttl.quote(), ttl.candles(), ttl.earnings(), ttl.profile(), ttl.search(),
                ttl.news(), ttl.marketStatus());
        return manager;
    }

    private static CaffeineCache cache(String name, Duration ttl, int maxSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .recordStats()
                .build());
    }
}
