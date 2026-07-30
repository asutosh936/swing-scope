package com.swingscope.service.marketdata.finnhub;

import com.swingscope.config.MarketDataProperties;
import com.swingscope.domain.marketdata.CompanyProfile;
import com.swingscope.domain.marketdata.EarningsEvent;
import com.swingscope.domain.marketdata.MarketStatus;
import com.swingscope.domain.marketdata.NewsItem;
import com.swingscope.service.marketdata.MarketDataException;
import com.swingscope.service.marketdata.MarketDataProvider;
import com.swingscope.service.marketdata.ProviderUnavailableException;
import com.swingscope.service.marketdata.RateLimitedException;
import com.swingscope.service.marketdata.UnknownSymbolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FinnhubClientTest {

    private static final String BASE = "https://finnhub.io/api/v1";

    private MockRestServiceServer server;
    private FinnhubClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new FinnhubClient(builder, properties("test-token"));
    }

    private static MarketDataProperties properties(String apiKey) {
        return new MarketDataProperties(null,
                new MarketDataProperties.Provider(BASE, apiKey, true, 2, Duration.ZERO, 0), null);
    }

    // ------------------------------------------------------------------------------ capability

    @Test
    @DisplayName("candles are deliberately absent — they are premium-only at Finnhub now")
    void doesNotClaimCandles() {
        assertThat(client.name()).isEqualTo("finnhub");
        assertThat(client.capabilities()).containsExactlyInAnyOrder(
                MarketDataProvider.Capability.EARNINGS,
                MarketDataProvider.Capability.MARKET_STATUS,
                MarketDataProvider.Capability.COMPANY_PROFILE,
                MarketDataProvider.Capability.COMPANY_NEWS);
        assertThat(client.supports(MarketDataProvider.Capability.DAILY_CANDLES)).isFalse();

        assertThatThrownBy(() -> client.getDailyCandles("AAPL", 250))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("finnhub does not provide DAILY_CANDLES");
    }

    @Test
    void disabledProviderIsUnavailable() {
        MarketDataProperties disabled = new MarketDataProperties(null,
                new MarketDataProperties.Provider(BASE, "token", false, 2, Duration.ZERO, 0), null);
        FinnhubClient offline = new FinnhubClient(RestClient.builder(), disabled);

        assertThat(offline.isAvailable()).isFalse();
        assertThat(offline.supports(MarketDataProvider.Capability.EARNINGS)).isFalse();
    }

    // -------------------------------------------------------------------------------- earnings

    @Test
    void parsesAndSortsTheEarningsCalendar() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/calendar/earnings")))
                .andExpect(queryParam("symbol", "AAPL"))
                .andExpect(queryParam("token", "test-token"))
                .andRespond(withSuccess("""
                        {
                          "earningsCalendar": [
                            {"date":"2026-10-30","epsActual":null,"epsEstimate":1.55,"hour":"amc","quarter":4,"symbol":"AAPL","year":2026},
                            {"date":"2026-07-30","epsActual":null,"epsEstimate":1.42,"hour":"amc","quarter":3,"symbol":"AAPL","year":2026}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<EarningsEvent> events = client.getEarnings("AAPL",
                LocalDate.of(2026, 7, 28), LocalDate.of(2026, 12, 31));

        assertThat(events).hasSize(2);
        assertThat(events.get(0).date()).isEqualTo(LocalDate.of(2026, 7, 30));   // sorted ascending
        assertThat(events.get(0).hour()).isEqualTo("amc");
        assertThat(events.get(0).quarter()).isEqualTo(3);
        assertThat(events.get(0).year()).isEqualTo(2026);
        assertThat(events.get(1).date()).isEqualTo(LocalDate.of(2026, 10, 30));
        server.verify();
    }

    @Test
    void noScheduledEarningsIsAnEmptyListNotAnError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/calendar/earnings")))
                .andRespond(withSuccess("{\"earningsCalendar\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.getEarnings("AAPL", LocalDate.now(), LocalDate.now().plusDays(90)))
                .isEmpty();
    }

    @Test
    void entriesWithAnUnparseableDateAreSkippedRatherThanFailingTheCall() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/calendar/earnings")))
                .andRespond(withSuccess("""
                        {"earningsCalendar":[
                          {"date":"garbage","symbol":"AAPL"},
                          {"date":"2026-07-30","symbol":"AAPL"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<EarningsEvent> events = client.getEarnings("AAPL", LocalDate.now(), LocalDate.now());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).date()).isEqualTo(LocalDate.of(2026, 7, 30));
    }

    // --------------------------------------------------------------------------- market status

    @Test
    void parsesMarketStatus() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/stock/market-status")))
                .andExpect(queryParam("exchange", "US"))
                .andRespond(withSuccess("""
                        {"exchange":"US","holiday":null,"isOpen":true,"session":"regular","timezone":"America/New_York"}
                        """, MediaType.APPLICATION_JSON));

        MarketStatus status = client.getMarketStatus();

        assertThat(status.exchange()).isEqualTo("US");
        assertThat(status.open()).isTrue();
        assertThat(status.session()).isEqualTo("regular");
        assertThat(status.holiday()).isNull();
    }

    @Test
    void aClosedMarketWithAHolidayIsReportedAsSuch() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/stock/market-status")))
                .andRespond(withSuccess("""
                        {"exchange":"US","holiday":"Independence Day","isOpen":false,"session":null}
                        """, MediaType.APPLICATION_JSON));

        MarketStatus status = client.getMarketStatus();

        assertThat(status.open()).isFalse();
        assertThat(status.holiday()).isEqualTo("Independence Day");
    }

    // --------------------------------------------------------------------------------- profile

    @Test
    void parsesCompanyProfileForMarketCap() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/stock/profile2")))
                .andExpect(queryParam("symbol", "AAPL"))
                .andRespond(withSuccess("""
                        {
                          "country":"US","currency":"USD","exchange":"NASDAQ NMS - GLOBAL MARKET",
                          "finnhubIndustry":"Technology","ipo":"1980-12-12",
                          "marketCapitalization":3250000.5,"name":"Apple Inc","ticker":"AAPL"
                        }
                        """, MediaType.APPLICATION_JSON));

        CompanyProfile profile = client.getCompanyProfile("AAPL");

        assertThat(profile.symbol()).isEqualTo("AAPL");
        assertThat(profile.name()).isEqualTo("Apple Inc");
        assertThat(profile.marketCap()).isEqualByComparingTo("3250000.5");
        assertThat(profile.industry()).isEqualTo("Technology");
    }

    @Test
    @DisplayName("Finnhub answers an unknown ticker with HTTP 200 and an empty object")
    void anEmptyProfileObjectIsAnUnknownSymbol() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/stock/profile2")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getCompanyProfile("ZZZZ"))
                .isInstanceOf(UnknownSymbolException.class)
                .hasMessageContaining("ZZZZ");
    }

    // ----------------------------------------------------------------------------------- errors

    @Test
    @DisplayName("HTTP 403 explains that the endpoint needs a paid plan — the candles trap")
    void premiumOnlyEndpointGivesAClearMessage() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/stock/profile2")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.getCompanyProfile("AAPL"))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("paid plan");
    }

    @Test
    void invalidTokenIsReportedAsUnavailableNotAsABadSymbol() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/stock/market-status")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(client::getMarketStatus)
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("check the API key");
    }

    @Test
    void rateLimitsAreRetriedThenSurfaced() {
        server.expect(ExpectedCount.times(3),
                        requestTo(org.hamcrest.Matchers.startsWith(BASE + "/calendar/earnings")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.getEarnings("AAPL", LocalDate.now(), LocalDate.now()))
                .isInstanceOf(RateLimitedException.class);
        server.verify();
    }

    @Test
    void emptyBodiesOnTheOptionalEndpointsDegradeGracefully() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/calendar/earnings")))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        assertThat(client.getEarnings("AAPL", LocalDate.now(), LocalDate.now())).isEmpty();
        server.reset();

        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/calendar/earnings")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(client.getEarnings("AAPL", LocalDate.now(), LocalDate.now())).isEmpty();
        server.reset();

        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/stock/profile2")))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.getCompanyProfile("AAPL"))
                .isInstanceOf(UnknownSymbolException.class);
    }

    @Test
    @DisplayName("an entry without its own symbol inherits the one that was asked for")
    void entriesMissingASymbolFallBackToTheQuery() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/calendar/earnings")))
                .andRespond(withSuccess("""
                        {"earningsCalendar":[{"date":"2026-07-30","hour":"bmo"}]}
                        """, MediaType.APPLICATION_JSON));

        List<EarningsEvent> events = client.getEarnings("AAPL", LocalDate.now(), LocalDate.now());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).symbol()).isEqualTo("AAPL");
    }

    @Test
    void marketStatusWithoutAnExchangeDefaultsToUs() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/stock/market-status")))
                .andRespond(withSuccess("{\"isOpen\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.getMarketStatus().exchange()).isEqualTo("US");
    }

    @Test
    void anEmptyMarketStatusBodyIsAnError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/stock/market-status")))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::getMarketStatus)
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("empty market-status response");
    }

    // ---------------------------------------------------------------------------- company news

    @Test
    @DisplayName("news comes back newest-first with epoch seconds turned into instants")
    void companyNewsIsSortedNewestFirst() {
        // The endpoint returns a bare JSON array, not an object wrapper.
        String json = """
                [
                  {"category":"company","datetime":1700000000,"headline":"Older story",
                   "id":1,"related":"AAPL","source":"Reuters","summary":"older summary",
                   "url":"https://example.com/1"},
                  {"category":"company","datetime":1700086400,"headline":"Newer story",
                   "id":2,"related":"AAPL","source":"CNBC","summary":"newer summary",
                   "url":"https://example.com/2"}
                ]
                """;
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/company-news")))
                .andExpect(queryParam("symbol", "AAPL"))
                .andExpect(queryParam("token", "test-token"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<NewsItem> news = client.getCompanyNews("AAPL",
                LocalDate.of(2026, 7, 22), LocalDate.of(2026, 7, 29));

        assertThat(news).hasSize(2);
        assertThat(news.get(0).headline()).isEqualTo("Newer story");
        assertThat(news.get(0).publishedAt()).isEqualTo(Instant.ofEpochSecond(1700086400L));
        assertThat(news.get(0).source()).isEqualTo("CNBC");
        assertThat(news.get(0).symbol()).isEqualTo("AAPL");
        assertThat(news.get(1).headline()).isEqualTo("Older story");
        assertThat(news.get(1).url()).isEqualTo("https://example.com/1");
    }

    @Test
    void anEmptyNewsArrayIsNotAnError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/company-news")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.getCompanyNews("AAPL", LocalDate.now().minusDays(7), LocalDate.now()))
                .isEmpty();
    }

    @Test
    @DisplayName("a story with no 'related' field falls back to the requested symbol")
    void newsWithoutRelatedFallsBackToTheSymbol() {
        String json = """
                [{"category":"company","datetime":1700000000,"headline":"No related field",
                  "id":1,"source":"AP","summary":"s","url":"https://example.com/x"}]
                """;
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/company-news")))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<NewsItem> news = client.getCompanyNews("MSFT", LocalDate.now().minusDays(7), LocalDate.now());

        assertThat(news).singleElement()
                .satisfies(n -> assertThat(n.symbol()).isEqualTo("MSFT"));
    }

    @Test
    @DisplayName("a story with a null datetime sorts last instead of blowing up")
    void newsWithoutATimestampSortsLast() {
        String json = """
                [{"category":"company","headline":"Undated","id":1,"related":"AAPL",
                  "source":"AP","summary":"s","url":"https://example.com/x"},
                 {"category":"company","datetime":1700000000,"headline":"Dated","id":2,
                  "related":"AAPL","source":"AP","summary":"s","url":"https://example.com/y"}]
                """;
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/company-news")))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<NewsItem> news = client.getCompanyNews("AAPL", LocalDate.now().minusDays(7), LocalDate.now());

        assertThat(news).hasSize(2);
        assertThat(news.get(0).headline()).isEqualTo("Dated");
        assertThat(news.get(1).publishedAt()).isNull();
    }

    @Test
    void newsRateLimitSurfacesAsRateLimited() {
        server.expect(ExpectedCount.times(3),
                        requestTo(org.hamcrest.Matchers.startsWith(BASE + "/company-news")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.getCompanyNews("AAPL",
                LocalDate.now().minusDays(7), LocalDate.now()))
                .isInstanceOf(RateLimitedException.class);
    }
}
