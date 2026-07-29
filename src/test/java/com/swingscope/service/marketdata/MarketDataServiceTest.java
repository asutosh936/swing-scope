package com.swingscope.service.marketdata;

import com.swingscope.domain.marketdata.Candle;
import com.swingscope.domain.marketdata.Candles;
import com.swingscope.domain.marketdata.CompanyProfile;
import com.swingscope.domain.marketdata.EarningsEvent;
import com.swingscope.domain.marketdata.MarketSnapshot;
import com.swingscope.domain.marketdata.MarketStatus;
import com.swingscope.domain.marketdata.NewsItem;
import com.swingscope.domain.marketdata.Quote;
import com.swingscope.domain.marketdata.SymbolMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketDataServiceTest {

    private final EmaCalculator ema = new EmaCalculator();

    // ------------------------------------------------------------------------------ test doubles

    /** A provider whose responses are handed to it, so the service can be exercised offline. */
    private static class FakeProvider implements MarketDataProvider {
        private final String name;
        private final Set<Capability> capabilities;
        private boolean available = true;
        Quote quote;
        Candles candles;
        CompanyProfile profile;
        List<EarningsEvent> earnings = List.of();
        MarketStatus status;
        List<SymbolMatch> matches = List.of();
        RuntimeException profileFailure;
        RuntimeException earningsFailure;
        List<NewsItem> news = List.of();
        LocalDate newsFrom;
        LocalDate newsTo;
        int quoteCalls;

        FakeProvider(String name, Set<Capability> capabilities) {
            this.name = name;
            this.capabilities = capabilities;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Set<Capability> capabilities() {
            return capabilities;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public Quote getQuote(String symbol) {
            quoteCalls++;
            return quote;
        }

        @Override
        public Candles getDailyCandles(String symbol, int outputSize) {
            return candles;
        }

        @Override
        public List<SymbolMatch> search(String query) {
            return matches;
        }

        @Override
        public List<EarningsEvent> getEarnings(String symbol, LocalDate from, LocalDate to) {
            if (earningsFailure != null) {
                throw earningsFailure;
            }
            return earnings;
        }

        @Override
        public MarketStatus getMarketStatus() {
            return status;
        }

        @Override
        public CompanyProfile getCompanyProfile(String symbol) {
            if (profileFailure != null) {
                throw profileFailure;
            }
            return profile;
        }

        @Override
        public List<NewsItem> getCompanyNews(String symbol, LocalDate from, LocalDate to) {
            newsFrom = from;
            newsTo = to;
            return news;
        }
    }

    /** A rising series: 250 bars climbing steadily, so price > EMA50 > EMA200. */
    private static Candles risingCandles(String symbol, int bars) {
        List<Candle> list = new ArrayList<>();
        LocalDate date = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < bars; i++) {
            BigDecimal close = new BigDecimal(100 + i);
            list.add(new Candle(date.plusDays(i), close, close, close, close, 1_000_000L));
        }
        return new Candles(symbol, list);
    }

    private static Candles fallingCandles(String symbol, int bars) {
        List<Candle> list = new ArrayList<>();
        LocalDate date = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < bars; i++) {
            BigDecimal close = new BigDecimal(500 - i);
            list.add(new Candle(date.plusDays(i), close, close, close, close, 1_000_000L));
        }
        return new Candles(symbol, list);
    }

    private FakeProvider primary() {
        FakeProvider p = new FakeProvider("fake-primary", Set.of(
                MarketDataProvider.Capability.QUOTE,
                MarketDataProvider.Capability.DAILY_CANDLES,
                MarketDataProvider.Capability.SYMBOL_SEARCH));
        p.quote = new Quote("AAPL", new BigDecimal("400.00"), new BigDecimal("396.00"),
                new BigDecimal("4.00"), new BigDecimal("1.01"), 5_000_000L, 4_000_000L);
        p.candles = risingCandles("AAPL", 250);
        return p;
    }

    private FakeProvider secondary() {
        FakeProvider p = new FakeProvider("fake-secondary", Set.of(
                MarketDataProvider.Capability.EARNINGS,
                MarketDataProvider.Capability.MARKET_STATUS,
                MarketDataProvider.Capability.COMPANY_PROFILE,
                MarketDataProvider.Capability.COMPANY_NEWS));
        p.profile = new CompanyProfile("AAPL", "Apple Inc", "NASDAQ", new BigDecimal("3250000"), "Tech");
        p.status = new MarketStatus("US", true, "regular", null);
        return p;
    }

    private MarketDataService service(MarketDataProvider... providers) {
        return new MarketDataService(List.of(providers), ema);
    }

    // -------------------------------------------------------------------------------- routing

    @Test
    @DisplayName("each capability goes to a provider that actually offers it")
    void routesByCapability() {
        FakeProvider primary = primary();
        FakeProvider secondary = secondary();
        MarketDataService service = service(primary, secondary);

        assertThat(service.provider(MarketDataProvider.Capability.QUOTE)).isSameAs(primary);
        assertThat(service.provider(MarketDataProvider.Capability.DAILY_CANDLES)).isSameAs(primary);
        assertThat(service.provider(MarketDataProvider.Capability.EARNINGS)).isSameAs(secondary);
        assertThat(service.provider(MarketDataProvider.Capability.COMPANY_PROFILE)).isSameAs(secondary);
        assertThat(service.provider(MarketDataProvider.Capability.MARKET_STATUS)).isSameAs(secondary);
    }

    @Test
    void anUnavailableProviderIsSkipped() {
        FakeProvider primary = primary();
        primary.available = false;
        MarketDataService service = service(primary);

        assertThatThrownBy(() -> service.provider(MarketDataProvider.Capability.QUOTE))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("no configured provider offers QUOTE");
    }

    @Test
    void missingCapabilityAcrossAllProvidersFailsClearly() {
        MarketDataService service = service(primary());

        assertThatThrownBy(() -> service.provider(MarketDataProvider.Capability.EARNINGS))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("check the API keys");
    }

    @Test
    void delegatesTheSimpleLookups() {
        FakeProvider primary = primary();
        FakeProvider secondary = secondary();
        primary.matches = List.of(new SymbolMatch("AAPL", "Apple Inc", "NASDAQ", "Common Stock"));
        secondary.earnings = List.of(new EarningsEvent("AAPL", LocalDate.now().plusDays(20), "amc", 3, 2026));
        MarketDataService service = service(primary, secondary);

        assertThat(service.getQuote("AAPL").price()).isEqualByComparingTo("400.00");
        assertThat(service.getDailyCandles("AAPL", 250).size()).isEqualTo(250);
        assertThat(service.search("apple")).hasSize(1);
        assertThat(service.getUpcomingEarnings("AAPL")).hasSize(1);
        assertThat(service.getCompanyProfile("AAPL").marketCap()).isEqualByComparingTo("3250000");
        assertThat(service.getMarketStatus().open()).isTrue();
    }

    // ------------------------------------------------------------------------------- snapshot

    @Test
    @DisplayName("a full snapshot carries price, all three EMAs, cap and earnings")
    void assemblesACompleteSnapshot() {
        FakeProvider primary = primary();
        FakeProvider secondary = secondary();
        secondary.earnings = List.of(
                new EarningsEvent("AAPL", LocalDate.now().plusDays(40), "amc", 3, 2026));
        MarketDataService service = service(primary, secondary);

        MarketSnapshot snapshot = service.getSnapshot(" aapl ");

        assertThat(snapshot.symbol()).isEqualTo("AAPL");           // trimmed and upper-cased
        assertThat(snapshot.price()).isEqualByComparingTo("400.00");
        assertThat(snapshot.changePercent()).isEqualByComparingTo("1.01");
        assertThat(snapshot.ema20()).isNotNull();
        assertThat(snapshot.ema50()).isNotNull();
        assertThat(snapshot.ema200()).isNotNull();
        assertThat(snapshot.candlesAvailable()).isEqualTo(250);
        assertThat(snapshot.marketCap()).isEqualByComparingTo("3250000");
        assertThat(snapshot.nextEarningsDate()).isEqualTo(LocalDate.now().plusDays(40));
        assertThat(snapshot.volume()).isEqualTo(5_000_000L);
        assertThat(snapshot.warnings()).isEmpty();
    }

    @Test
    @DisplayName("a rising series passes the trend test, a falling one fails it")
    void trendTestFollowsTheEmaStack() {
        FakeProvider up = primary();
        MarketDataService rising = service(up, secondary());
        assertThat(rising.getSnapshot("AAPL").inUptrend()).isTrue();

        FakeProvider down = primary();
        down.candles = fallingCandles("AAPL", 250);
        down.quote = new Quote("AAPL", new BigDecimal("251.00"), new BigDecimal("255.00"),
                new BigDecimal("-4.00"), new BigDecimal("-1.57"), 5_000_000L, 4_000_000L);
        MarketDataService falling = service(down, secondary());
        assertThat(falling.getSnapshot("AAPL").inUptrend()).isFalse();
    }

    @Test
    @DisplayName("short history leaves the trend inconclusive and warns rather than guessing")
    void notEnoughHistoryMeansAnUnknownTrend() {
        FakeProvider primary = primary();
        primary.candles = risingCandles("AAPL", 60);      // enough for EMA20/50, not EMA200
        MarketDataService service = service(primary, secondary());

        MarketSnapshot snapshot = service.getSnapshot("AAPL");

        assertThat(snapshot.ema20()).isNotNull();
        assertThat(snapshot.ema50()).isNotNull();
        assertThat(snapshot.ema200()).isNull();
        assertThat(snapshot.inUptrend()).isNull();
        assertThat(snapshot.candlesAvailable()).isEqualTo(60);
        assertThat(snapshot.warnings()).anyMatch(w -> w.contains("EMA200 needs 200"));
    }

    @Test
    @DisplayName("market cap failure degrades the snapshot with a warning instead of failing it")
    void optionalDataFailureIsNonFatal() {
        FakeProvider primary = primary();
        FakeProvider secondary = secondary();
        secondary.profileFailure = new ProviderUnavailableException("fake-secondary", "no key configured");
        secondary.earningsFailure = new RateLimitedException("fake-secondary", "60/min exceeded");
        MarketDataService service = service(primary, secondary);

        MarketSnapshot snapshot = service.getSnapshot("AAPL");

        assertThat(snapshot.price()).isEqualByComparingTo("400.00");   // core data still there
        assertThat(snapshot.marketCap()).isNull();
        assertThat(snapshot.nextEarningsDate()).isNull();
        assertThat(snapshot.earningsWithin3Days()).isFalse();
        assertThat(snapshot.warnings()).hasSize(2);
        assertThat(snapshot.warnings().get(0)).contains("market cap unavailable");
        assertThat(snapshot.warnings().get(1)).contains("earnings date unavailable");
    }

    @Test
    @DisplayName("a quote failure is fatal — there is nothing to reason about without it")
    void coreDataFailureIsFatal() {
        FakeProvider primary = primary();
        MarketDataService service = service(primary, secondary());
        primary.available = false;

        assertThatThrownBy(() -> service.getSnapshot("AAPL"))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void earningsInThePastAreIgnoredWhenPickingTheNextDate() {
        FakeProvider primary = primary();
        FakeProvider secondary = secondary();
        secondary.earnings = List.of(
                new EarningsEvent("AAPL", LocalDate.now().minusDays(10), "amc", 2, 2026),
                new EarningsEvent("AAPL", LocalDate.now().plusDays(45), "amc", 3, 2026));
        MarketDataService service = service(primary, secondary);

        assertThat(service.getSnapshot("AAPL").nextEarningsDate())
                .isEqualTo(LocalDate.now().plusDays(45));
    }

    // ------------------------------------------------------------------------- mechanical rules

    @Test
    void bigMoverFlagTripsAboveFivePercentEitherWay() {
        assertThat(MarketDataService.isBigMover(new BigDecimal("5.3"))).isTrue();
        assertThat(MarketDataService.isBigMover(new BigDecimal("-5.3"))).isTrue();
        assertThat(MarketDataService.isBigMover(new BigDecimal("5.0"))).isFalse();   // strictly >
        assertThat(MarketDataService.isBigMover(new BigDecimal("4.99"))).isFalse();
        assertThat(MarketDataService.isBigMover(null)).isFalse();
    }

    @Test
    void earningsWindowCoversTodayThroughThreeDaysOut() {
        LocalDate today = LocalDate.now();

        assertThat(MarketDataService.isEarningsWithinBlockWindow(today)).isTrue();
        assertThat(MarketDataService.isEarningsWithinBlockWindow(today.plusDays(3))).isTrue();
        assertThat(MarketDataService.isEarningsWithinBlockWindow(today.plusDays(4))).isFalse();
        assertThat(MarketDataService.isEarningsWithinBlockWindow(today.minusDays(1))).isFalse();
        assertThat(MarketDataService.isEarningsWithinBlockWindow(null)).isFalse();
    }

    @Test
    void trendTestNeedsAllThreeInputs() {
        BigDecimal price = new BigDecimal("100");
        BigDecimal ema50 = new BigDecimal("90");
        BigDecimal ema200 = new BigDecimal("80");

        assertThat(MarketDataService.trendTest(price, ema50, ema200)).isTrue();
        assertThat(MarketDataService.trendTest(new BigDecimal("85"), ema50, ema200)).isFalse();
        assertThat(MarketDataService.trendTest(price, new BigDecimal("70"), ema200)).isFalse();
        assertThat(MarketDataService.trendTest(null, ema50, ema200)).isNull();
        assertThat(MarketDataService.trendTest(price, null, ema200)).isNull();
        assertThat(MarketDataService.trendTest(price, ema50, null)).isNull();
    }

    @Test
    void snapshotFlagsABigMoverWithEarningsImminent() {
        FakeProvider primary = primary();
        primary.quote = new Quote("AAPL", new BigDecimal("400.00"), new BigDecimal("380.00"),
                new BigDecimal("20.00"), new BigDecimal("5.26"), 9_000_000L, 4_000_000L);
        FakeProvider secondary = secondary();
        secondary.earnings = List.of(
                new EarningsEvent("AAPL", LocalDate.now().plusDays(2), "amc", 3, 2026));
        MarketDataService service = service(primary, secondary);

        MarketSnapshot snapshot = service.getSnapshot("AAPL");

        assertThat(snapshot.bigMover()).isTrue();
        assertThat(snapshot.earningsWithin3Days()).isTrue();
    }

    @Test
    void nullSymbolIsHandledWithoutBlowingUp() {
        FakeProvider primary = primary();
        MarketDataService service = service(primary, secondary());

        assertThat(service.getSnapshot(null).symbol()).isEmpty();
    }

    // ------------------------------------------------------------------------------ company news

    @Test
    @DisplayName("news routes to the provider that offers COMPANY_NEWS, over the requested lookback")
    void recentNewsUsesTheNewsCapableProvider() {
        FakeProvider primary = primary();
        FakeProvider secondary = secondary();
        secondary.news = List.of(new NewsItem("AAPL", "Something happened", "summary",
                "Reuters", "https://example.com/1", "company", Instant.ofEpochSecond(1700086400L)));
        MarketDataService service = service(primary, secondary);

        List<NewsItem> news = service.getRecentNews("AAPL", 7);

        assertThat(news).singleElement()
                .satisfies(n -> assertThat(n.headline()).isEqualTo("Something happened"));
        assertThat(secondary.newsTo).isEqualTo(LocalDate.now());
        assertThat(secondary.newsFrom).isEqualTo(LocalDate.now().minusDays(7));
    }

    @Test
    @DisplayName("no news-capable provider is a clear configuration error, not a silent empty list")
    void newsWithoutACapableProviderFails() {
        MarketDataService service = service(primary());

        assertThatThrownBy(() -> service.getRecentNews("AAPL", 7))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("COMPANY_NEWS");
    }

    @Test
    @DisplayName("news never influences the snapshot — it is context for the human only")
    void snapshotIgnoresNews() {
        FakeProvider primary = primary();
        FakeProvider secondary = secondary();
        secondary.news = List.of(new NewsItem("AAPL", "Scary headline", "s", "AP",
                "https://example.com/x", "company", Instant.now()));
        MarketDataService service = service(primary, secondary);

        MarketSnapshot snapshot = service.getSnapshot("AAPL");

        assertThat(snapshot.warnings()).isEmpty();
        assertThat(snapshot.inUptrend()).isTrue();
        assertThat(secondary.newsFrom).isNull();   // the snapshot never asked for news
    }
}
