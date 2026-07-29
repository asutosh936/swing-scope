package com.swingscope.service.marketdata;

import com.swingscope.domain.marketdata.Candles;
import com.swingscope.domain.marketdata.CompanyProfile;
import com.swingscope.domain.marketdata.EarningsEvent;
import com.swingscope.domain.marketdata.MarketSnapshot;
import com.swingscope.domain.marketdata.MarketStatus;
import com.swingscope.domain.marketdata.NewsItem;
import com.swingscope.domain.marketdata.Quote;
import com.swingscope.domain.marketdata.SymbolMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Routes each request to a provider that supports the capability, and assembles the combined
 * snapshot the filters need.
 *
 * <p>Nothing here decides anything. It reports facts — price, EMAs, whether the trend test passes
 * arithmetically, whether earnings fall inside the block window. The verdict stays with the human.
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    /** EMA200 needs 200 closes; ask for a margin so holidays and halts don't leave it short. */
    static final int CANDLE_HISTORY = 250;

    private static final int EMA_SHORT = 20;
    private static final int EMA_MID = 50;
    private static final int EMA_LONG = 200;

    private static final BigDecimal BIG_MOVE_PERCENT = new BigDecimal("5");
    private static final int EARNINGS_BLOCK_DAYS = 3;
    private static final int EARNINGS_LOOKAHEAD_DAYS = 90;

    private final List<MarketDataProvider> providers;
    private final EmaCalculator emaCalculator;

    public MarketDataService(List<MarketDataProvider> providers, EmaCalculator emaCalculator) {
        this.providers = List.copyOf(providers);
        this.emaCalculator = emaCalculator;
        log.info("MarketDataService wired with {} provider(s): {}", providers.size(),
                providers.stream()
                        .map(p -> "%s[available=%s, capabilities=%s]"
                                .formatted(p.name(), p.isAvailable(), p.capabilities()))
                        .toList());
    }

    // ---------------------------------------------------------------- single-capability lookups

    @Cacheable(cacheNames = "quotes", key = "#symbol")
    public Quote getQuote(String symbol) {
        return provider(MarketDataProvider.Capability.QUOTE).getQuote(symbol);
    }

    @Cacheable(cacheNames = "candles", key = "#symbol + ':' + #outputSize")
    public Candles getDailyCandles(String symbol, int outputSize) {
        return provider(MarketDataProvider.Capability.DAILY_CANDLES)
                .getDailyCandles(symbol, outputSize);
    }

    @Cacheable(cacheNames = "search", key = "#query")
    public List<SymbolMatch> search(String query) {
        return provider(MarketDataProvider.Capability.SYMBOL_SEARCH).search(query);
    }

    @Cacheable(cacheNames = "earnings", key = "#symbol")
    public List<EarningsEvent> getUpcomingEarnings(String symbol) {
        LocalDate today = LocalDate.now();
        return provider(MarketDataProvider.Capability.EARNINGS)
                .getEarnings(symbol, today, today.plusDays(EARNINGS_LOOKAHEAD_DAYS));
    }

    @Cacheable(cacheNames = "profile", key = "#symbol")
    public CompanyProfile getCompanyProfile(String symbol) {
        return provider(MarketDataProvider.Capability.COMPANY_PROFILE).getCompanyProfile(symbol);
    }

    /**
     * Recent stories, newest first — context for the human and the input to Phase 5's AI summary.
     * Never consulted by any filter or sizing rule.
     */
    @Cacheable(cacheNames = "news", key = "#symbol + ':' + #lookbackDays")
    public List<NewsItem> getRecentNews(String symbol, int lookbackDays) {
        LocalDate today = LocalDate.now();
        return provider(MarketDataProvider.Capability.COMPANY_NEWS)
                .getCompanyNews(symbol, today.minusDays(lookbackDays), today);
    }

    @Cacheable(cacheNames = "marketStatus")
    public MarketStatus getMarketStatus() {
        return provider(MarketDataProvider.Capability.MARKET_STATUS).getMarketStatus();
    }

    // ------------------------------------------------------------------------------- assembly

    /**
     * The combined snapshot for one symbol.
     *
     * <p>Quote and candles are required — without them there is nothing to reason about, so a
     * failure propagates. Market cap and earnings are best-effort: if the secondary provider is
     * down or unconfigured, the snapshot still returns with a warning attached rather than failing
     * the whole request.
     */
    public MarketSnapshot getSnapshot(String rawSymbol) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase();
        log.info("Assembling snapshot for {}", symbol);
        long startedAt = System.nanoTime();

        List<String> warnings = new ArrayList<>();

        Quote quote = getQuote(symbol);
        Candles candles = getDailyCandles(symbol, CANDLE_HISTORY);
        List<BigDecimal> closes = candles.closes();

        BigDecimal ema20 = emaCalculator.ema(closes, EMA_SHORT);
        BigDecimal ema50 = emaCalculator.ema(closes, EMA_MID);
        BigDecimal ema200 = emaCalculator.ema(closes, EMA_LONG);

        if (ema200 == null) {
            String warning = "only %d daily bars available — EMA200 needs %d, trend test is inconclusive"
                    .formatted(candles.size(), EMA_LONG);
            warnings.add(warning);
            log.warn("{}: {}", symbol, warning);
        }

        Boolean inUptrend = trendTest(quote.price(), ema50, ema200);

        BigDecimal marketCap = null;
        try {
            CompanyProfile profile = getCompanyProfile(symbol);
            marketCap = profile.marketCap();
        } catch (MarketDataException e) {
            warnings.add("market cap unavailable: " + e.getMessage());
            log.warn("{}: market cap unavailable — {}", symbol, e.getMessage());
        }

        LocalDate nextEarnings = null;
        try {
            nextEarnings = getUpcomingEarnings(symbol).stream()
                    .map(EarningsEvent::date)
                    .filter(d -> d != null && !d.isBefore(LocalDate.now()))
                    .min(Comparator.naturalOrder())
                    .orElse(null);
        } catch (MarketDataException e) {
            warnings.add("earnings date unavailable: " + e.getMessage());
            log.warn("{}: earnings date unavailable — {}", symbol, e.getMessage());
        }

        boolean bigMover = isBigMover(quote.changePercent());
        boolean earningsSoon = isEarningsWithinBlockWindow(nextEarnings);

        MarketSnapshot snapshot = new MarketSnapshot(symbol, quote.price(), quote.changePercent(),
                ema20, ema50, ema200, quote.volume(), marketCap, nextEarnings,
                inUptrend, bigMover, earningsSoon, candles.size(), warnings);

        log.info("Snapshot {} assembled in {}ms: price={} change%={} ema20={} ema50={} ema200={} "
                        + "uptrend={} bigMover={} earnings={}{} warnings={}",
                symbol, (System.nanoTime() - startedAt) / 1_000_000,
                snapshot.price(), snapshot.changePercent(), ema20, ema50, ema200,
                inUptrend, bigMover, nextEarnings, earningsSoon ? " (INSIDE BLOCK WINDOW)" : "",
                warnings.size());
        return snapshot;
    }

    // ---------------------------------------------------------------------------------- rules

    /** Plan's trend test: price &gt; EMA50 AND EMA50 &gt; EMA200. Null when history is short. */
    static Boolean trendTest(BigDecimal price, BigDecimal ema50, BigDecimal ema200) {
        if (price == null || ema50 == null || ema200 == null) {
            return null;
        }
        return price.compareTo(ema50) > 0 && ema50.compareTo(ema200) > 0;
    }

    static boolean isBigMover(BigDecimal changePercent) {
        return changePercent != null && changePercent.abs().compareTo(BIG_MOVE_PERCENT) > 0;
    }

    static boolean isEarningsWithinBlockWindow(LocalDate nextEarnings) {
        if (nextEarnings == null) {
            return false;
        }
        LocalDate today = LocalDate.now();
        return !nextEarnings.isBefore(today)
                && !nextEarnings.isAfter(today.plusDays(EARNINGS_BLOCK_DAYS));
    }

    /** First available provider that offers the capability. */
    MarketDataProvider provider(MarketDataProvider.Capability capability) {
        return providers.stream()
                .filter(p -> p.supports(capability))
                .findFirst()
                .orElseThrow(() -> new ProviderUnavailableException("none",
                        "no configured provider offers %s — check the API keys in the environment"
                                .formatted(capability)));
    }
}
