package com.swingscope.web;

import com.swingscope.domain.marketdata.MarketSnapshot;
import com.swingscope.domain.marketdata.MarketStatus;
import com.swingscope.domain.marketdata.SymbolMatch;
import com.swingscope.service.marketdata.MarketDataException;
import com.swingscope.service.marketdata.MarketDataService;
import com.swingscope.service.marketdata.ProviderUnavailableException;
import com.swingscope.service.marketdata.RateLimitedException;
import com.swingscope.service.marketdata.UnknownSymbolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MarketDataControllerTest {

    private MarketDataService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(MarketDataService.class);
        // Mirror Boot's JSON defaults so dates serialise as ISO strings, not [2026,10,30] arrays.
        var objectMapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(new MarketDataController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private static MarketSnapshot snapshot() {
        return new MarketSnapshot("AAPL",
                new BigDecimal("214.25"), new BigDecimal("3.00"),
                new BigDecimal("210.1234"), new BigDecimal("205.5678"), new BigDecimal("190.9876"),
                51_234_567L, 48_000_000L, new BigDecimal("3250000"), LocalDate.of(2026, 10, 30),
                true, false, false, 250, List.of());
    }

    @Test
    void returnsTheSnapshot() throws Exception {
        when(service.getSnapshot("AAPL")).thenReturn(snapshot());

        mockMvc.perform(get("/api/marketdata/AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.price").value(214.25))
                .andExpect(jsonPath("$.ema50").value(205.5678))
                .andExpect(jsonPath("$.ema200").value(190.9876))
                .andExpect(jsonPath("$.inUptrend").value(true))
                .andExpect(jsonPath("$.bigMover").value(false))
                .andExpect(jsonPath("$.nextEarningsDate").value("2026-10-30"))
                .andExpect(jsonPath("$.candlesAvailable").value(250));
    }

    @Test
    void returnsSearchResults() throws Exception {
        when(service.search("apple"))
                .thenReturn(List.of(new SymbolMatch("AAPL", "Apple Inc", "NASDAQ", "Common Stock")));

        mockMvc.perform(get("/api/marketdata/search").param("q", "apple"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].name").value("Apple Inc"));
    }

    @Test
    void returnsMarketStatus() throws Exception {
        when(service.getMarketStatus()).thenReturn(new MarketStatus("US", true, "regular", null));

        mockMvc.perform(get("/api/marketdata/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.session").value("regular"));
    }

    @Test
    @DisplayName("an unknown ticker is the caller's mistake — 404")
    void unknownSymbolIs404() throws Exception {
        when(service.getSnapshot(anyString()))
                .thenThrow(new UnknownSymbolException("twelvedata", "ZZZZ"));

        mockMvc.perform(get("/api/marketdata/ZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.provider").value("twelvedata"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ZZZZ")));
    }

    @Test
    @DisplayName("a spent free-tier budget is 429 with Retry-After")
    void rateLimitIs429() throws Exception {
        when(service.getSnapshot(anyString()))
                .thenThrow(new RateLimitedException("twelvedata", "800/day exhausted"));

        mockMvc.perform(get("/api/marketdata/AAPL"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.message").value("800/day exhausted"));
    }

    @Test
    void unconfiguredProviderIs503() throws Exception {
        when(service.getSnapshot(anyString()))
                .thenThrow(new ProviderUnavailableException("twelvedata", "no API key configured"));

        mockMvc.perform(get("/api/marketdata/AAPL"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    @DisplayName("anything else upstream is a bad gateway, not our 500")
    void otherProviderFailuresAre502() throws Exception {
        when(service.getSnapshot(anyString()))
                .thenThrow(new MarketDataException("twelvedata", "upstream returned HTTP 500"));

        mockMvc.perform(get("/api/marketdata/AAPL"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.message").value("upstream returned HTTP 500"));
    }
}
