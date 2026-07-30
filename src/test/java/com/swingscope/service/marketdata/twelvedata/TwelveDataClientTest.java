package com.swingscope.service.marketdata.twelvedata;

import com.swingscope.config.MarketDataProperties;
import com.swingscope.domain.marketdata.Candles;
import com.swingscope.domain.marketdata.Quote;
import com.swingscope.domain.marketdata.SymbolMatch;
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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TwelveDataClientTest {

    private static final String BASE = "https://api.twelvedata.com";

    private MockRestServiceServer server;
    private TwelveDataClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TwelveDataClient(builder, properties("test-key"));
    }

    private static MarketDataProperties properties(String apiKey) {
        // Zero backoff so the retry tests don't actually sleep.
        return new MarketDataProperties(
                new MarketDataProperties.Provider(BASE, apiKey, true, 2, Duration.ZERO, 0),
                null, null);
    }

    // ------------------------------------------------------------------------------ capability

    @Test
    void declaresOnlyTheCapabilitiesItActuallyServes() {
        assertThat(client.name()).isEqualTo("twelvedata");
        assertThat(client.capabilities()).containsExactlyInAnyOrder(
                MarketDataProvider.Capability.QUOTE,
                MarketDataProvider.Capability.DAILY_CANDLES,
                MarketDataProvider.Capability.SYMBOL_SEARCH);
        assertThat(client.isAvailable()).isTrue();
        assertThat(client.supports(MarketDataProvider.Capability.EARNINGS)).isFalse();
    }

    @Test
    @DisplayName("earnings is not offered here — it belongs to Finnhub")
    void unsupportedCapabilityFailsClearly() {
        assertThatThrownBy(() -> client.getEarnings("AAPL", LocalDate.now(), LocalDate.now()))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("twelvedata does not provide EARNINGS");
    }

    @Test
    void aMissingApiKeyMakesTheProviderUnusable() {
        RestClient.Builder builder = RestClient.builder();
        TwelveDataClient unconfigured = new TwelveDataClient(builder, properties("  "));

        assertThat(unconfigured.isAvailable()).isFalse();
        assertThatThrownBy(() -> unconfigured.getQuote("AAPL"))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("not configured");
    }

    // ----------------------------------------------------------------------------------- quote

    @Test
    void parsesAQuoteWhoseNumbersAllArriveAsStrings() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quote")))
                .andExpect(queryParam("symbol", "AAPL"))
                .andExpect(queryParam("apikey", "test-key"))
                .andRespond(withSuccess("""
                        {
                          "symbol": "AAPL",
                          "name": "Apple Inc",
                          "exchange": "NASDAQ",
                          "datetime": "2026-07-28",
                          "open": "210.00",
                          "high": "215.50",
                          "low": "209.10",
                          "close": "214.25",
                          "volume": "51234567",
                          "previous_close": "208.00",
                          "change": "6.25",
                          "percent_change": "3.00481",
                          "average_volume": "48000000",
                          "is_market_open": false
                        }
                        """, MediaType.APPLICATION_JSON));

        Quote quote = client.getQuote("AAPL");

        assertThat(quote.symbol()).isEqualTo("AAPL");
        assertThat(quote.price()).isEqualByComparingTo("214.25");
        assertThat(quote.previousClose()).isEqualByComparingTo("208.00");
        assertThat(quote.change()).isEqualByComparingTo("6.25");
        assertThat(quote.changePercent()).isEqualByComparingTo("3.00481");
        assertThat(quote.volume()).isEqualTo(51_234_567L);
        assertThat(quote.averageVolume()).isEqualTo(48_000_000L);
        server.verify();
    }

    @Test
    @DisplayName("a quote missing its close price is treated as an unknown symbol")
    void quoteWithoutACloseIsUnknown() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quote")))
                .andRespond(withSuccess("{\"symbol\":\"NOPE\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getQuote("NOPE"))
                .isInstanceOf(UnknownSymbolException.class);
    }

    @Test
    void unparseableNumbersDegradeToNullRatherThanBlowingUp() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quote")))
                .andRespond(withSuccess("""
                        {"symbol":"AAPL","close":"214.25","volume":"n/a","percent_change":""}
                        """, MediaType.APPLICATION_JSON));

        Quote quote = client.getQuote("AAPL");

        assertThat(quote.price()).isEqualByComparingTo("214.25");
        assertThat(quote.volume()).isNull();
        assertThat(quote.changePercent()).isNull();
    }

    // --------------------------------------------------------------------------------- candles

    @Test
    @DisplayName("candles come back newest-first and are flipped to chronological order")
    void candlesAreSortedOldestFirst() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/time_series")))
                .andExpect(queryParam("interval", "1day"))
                .andExpect(queryParam("outputsize", "250"))
                .andRespond(withSuccess("""
                        {
                          "meta": {"symbol": "AAPL", "interval": "1day"},
                          "values": [
                            {"datetime":"2026-07-28","open":"212.00","high":"215.50","low":"211.00","close":"214.25","volume":"51234567"},
                            {"datetime":"2026-07-27","open":"209.00","high":"212.00","low":"208.50","close":"211.00","volume":"44000000"},
                            {"datetime":"2026-07-24","open":"206.00","high":"209.50","low":"205.00","close":"208.00","volume":"39000000"}
                          ],
                          "status": "ok"
                        }
                        """, MediaType.APPLICATION_JSON));

        Candles candles = client.getDailyCandles("AAPL", 250);

        assertThat(candles.size()).isEqualTo(3);
        assertThat(candles.bars().get(0).date()).isEqualTo(LocalDate.of(2026, 7, 24));
        assertThat(candles.latest().date()).isEqualTo(LocalDate.of(2026, 7, 28));
        assertThat(candles.latest().close()).isEqualByComparingTo("214.25");
        assertThat(candles.closes())
                .containsExactly(new java.math.BigDecimal("208.00"),
                        new java.math.BigDecimal("211.00"),
                        new java.math.BigDecimal("214.25"));
        assertThat(candles.isEmpty()).isFalse();
        server.verify();
    }

    @Test
    void anEmptyCandleSeriesIsAnUnknownSymbol() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/time_series")))
                .andRespond(withSuccess("{\"values\":[],\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getDailyCandles("NOPE", 250))
                .isInstanceOf(UnknownSymbolException.class)
                .hasMessageContaining("NOPE");
    }

    @Test
    void anUnparseableCandleDateIsAHardFailure() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/time_series")))
                .andRespond(withSuccess("""
                        {"values":[{"datetime":"not-a-date","close":"1.00"}],"status":"ok"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getDailyCandles("AAPL", 250))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("unparseable candle date");
    }

    // ---------------------------------------------------------------------------------- search

    @Test
    void mapsSymbolSearchResults() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/symbol_search")))
                .andRespond(withSuccess("""
                        {
                          "data": [
                            {"symbol":"AAPL","instrument_name":"Apple Inc","exchange":"NASDAQ","instrument_type":"Common Stock","country":"United States"},
                            {"symbol":"AAPL.MX","instrument_name":"Apple Inc","exchange":"BMV","instrument_type":"Common Stock","country":"Mexico"}
                          ],
                          "status": "ok"
                        }
                        """, MediaType.APPLICATION_JSON));

        List<SymbolMatch> matches = client.search("apple");

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).symbol()).isEqualTo("AAPL");
        assertThat(matches.get(0).name()).isEqualTo("Apple Inc");
        assertThat(matches.get(0).exchange()).isEqualTo("NASDAQ");
        assertThat(matches.get(0).type()).isEqualTo("Common Stock");
    }

    @Test
    void searchWithNoDataArrayReturnsAnEmptyList() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/symbol_search")))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        assertThat(client.search("zzzz")).isEmpty();
    }

    // ---------------------------------------------------------------------- error-body handling

    @Test
    @DisplayName("HTTP 200 with an error payload is still an error — Twelve Data's habit")
    void errorPayloadOnHttp200IsHonoured() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quote")))
                .andRespond(withSuccess("""
                        {"code":400,"message":"**symbol** not found: ZZZZ","status":"error"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getQuote("ZZZZ"))
                .isInstanceOf(UnknownSymbolException.class);
    }

    @Test
    void aBadApiKeyPayloadReportsTheProviderAsUnavailable() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quote")))
                .andRespond(withSuccess("""
                        {"code":401,"message":"Invalid API key","status":"error"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getQuote("AAPL"))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("rejected the API key");
    }

    @Test
    void anUnrecognisedErrorPayloadBecomesAGenericMarketDataException() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quote")))
                .andRespond(withSuccess("""
                        {"code":500,"message":"something broke","status":"error"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getQuote("AAPL"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("something broke");
    }

    // ----------------------------------------------------------------------------- rate limits

    @Test
    @DisplayName("a 429 payload is retried, and a later success is returned")
    void retriesARateLimitedCallAndSucceeds() {
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quote")))
                .andRespond(withSuccess("""
                        {"code":429,"message":"API credits exceeded","status":"error"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quote")))
                .andRespond(withSuccess("{\"symbol\":\"AAPL\",\"close\":\"214.25\"}",
                        MediaType.APPLICATION_JSON));

        Quote quote = client.getQuote("AAPL");

        assertThat(quote.price()).isEqualByComparingTo("214.25");
        server.verify();
    }

    @Test
    @DisplayName("a persistent 429 gives up after the configured retries")
    void givesUpAfterExhaustingRetries() {
        server.expect(ExpectedCount.times(3), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quote")))
                .andRespond(withSuccess("""
                        {"code":429,"message":"API credits exceeded","status":"error"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getQuote("AAPL"))
                .isInstanceOf(RateLimitedException.class)
                .hasMessageContaining("rate limit reached");
        server.verify();   // exactly 1 attempt + 2 retries
    }

    @Test
    void anHttp429StatusIsAlsoTreatedAsARateLimit() {
        server.expect(ExpectedCount.times(3), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quote")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.getQuote("AAPL"))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void anHttp404IsAnUnknownSymbol() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quote")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getQuote("ZZZZ"))
                .isInstanceOf(UnknownSymbolException.class);
    }

    @Test
    @DisplayName("an empty body is treated as no data, not as a null-pointer waiting to happen")
    void emptyBodiesAreHandledOnEveryEndpoint() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quote")))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.getQuote("AAPL")).isInstanceOf(UnknownSymbolException.class);
        server.reset();

        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/time_series")))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.getDailyCandles("AAPL", 250))
                .isInstanceOf(UnknownSymbolException.class);
        server.reset();

        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/symbol_search")))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        assertThat(client.search("apple")).isEmpty();
    }

    @Test
    @DisplayName("fewer bars than requested still returns — it just cannot support EMA200")
    void aShortSeriesIsReturnedRatherThanRejected() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/time_series")))
                .andRespond(withSuccess("""
                        {"values":[{"datetime":"2026-07-28","close":"214.25","open":"212.00","high":"215.00","low":"211.00","volume":"100"}],"status":"ok"}
                        """, MediaType.APPLICATION_JSON));

        Candles candles = client.getDailyCandles("AAPL", 250);

        assertThat(candles.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unreachable host surfaces as a market data failure, not a raw transport error")
    void transportFailureIsWrapped() {
        RestClient.Builder builder = RestClient.builder();
        MarketDataProperties unreachable = new MarketDataProperties(
                new MarketDataProperties.Provider("http://localhost:1", "key", true, 0, Duration.ZERO, 0),
                null, null);
        TwelveDataClient client = new TwelveDataClient(builder, unreachable);

        assertThatThrownBy(() -> client.getQuote("AAPL"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("call failed");
    }

    @Test
    void anHttp500BecomesAMarketDataException() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/quote")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getQuote("AAPL"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("HTTP 500");
    }
}
