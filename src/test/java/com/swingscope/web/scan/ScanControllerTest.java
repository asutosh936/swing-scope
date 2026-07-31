package com.swingscope.web.scan;

import com.swingscope.domain.marketdata.MarketSnapshot;
import com.swingscope.domain.scan.ScanResult;
import com.swingscope.domain.scan.Tier;
import com.swingscope.service.marketdata.MarketDataService;
import com.swingscope.service.marketdata.UnknownSymbolException;
import com.swingscope.service.scan.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScanControllerTest {

    private final MockMvc mockMvc;

    @Autowired
    private WatchlistService watchlist;

    /** Stubbed so the scan never touches a real provider. */
    @MockBean
    private MarketDataService marketData;

    @Autowired
    ScanControllerTest(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @BeforeEach
    void stubMarketData() {
        when(marketData.getSnapshot(anyString(), anyBoolean())).thenAnswer(inv -> {
            String symbol = inv.getArgument(0);
            return switch (symbol) {
                case "AAPL" -> snapshot("AAPL", "40.00", "1.20", true, 5_000_000L, "3000000");
                case "THIN" -> snapshot("THIN", "40.00", "1.20", true, 100_000L, "500");
                case "DOWN" -> snapshot("DOWN", "30.00", "-1.00", false, 5_000_000L, "3000000");
                default -> throw new UnknownSymbolException("twelvedata", symbol);
            };
        });
    }

    private static MarketSnapshot snapshot(String symbol, String price, String changePct,
                                           boolean uptrend, Long volume, String capMillions) {
        return new MarketSnapshot(symbol, new BigDecimal(price), new BigDecimal(changePct),
                new BigDecimal("39.50"), new BigDecimal("38.00"), new BigDecimal("35.00"),
                volume, volume, new BigDecimal(capMillions), null, uptrend, false, false, 250, List.of());
    }

    // ------------------------------------------------------------------------------------- API

    @Test
    void scanApiTiersAPastedBlob() throws Exception {
        mockMvc.perform(post("/api/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"raw\":\"aapl, thin down\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.stocks[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$.stocks[0].tier").value("TIER1"))
                .andExpect(jsonPath("$.stocks[1].tier").value("TIER2"))
                .andExpect(jsonPath("$.stocks[2].tier").value("SKIP"))
                .andExpect(jsonPath("$.stocks[2].reason").value("below the 50-EMA"));
    }

    @Test
    void scanApiAlsoAcceptsAnExplicitList() throws Exception {
        mockMvc.perform(post("/api/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tickers\":[\"AAPL\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stocks[0].symbol").value("AAPL"));
    }

    @Test
    @DisplayName("a ticker with no data is reported as UNAVAILABLE, not as a failed scan")
    void unknownTickerDoesNotFailTheWholeScan() throws Exception {
        mockMvc.perform(post("/api/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"raw\":\"AAPL NOPE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stocks[0].tier").value("TIER1"))
                .andExpect(jsonPath("$.stocks[1].tier").value("UNAVAILABLE"));
    }

    @Test
    void scanWatchlistApiUsesTheSavedNames() throws Exception {
        watchlist.add("AAPL", null);

        mockMvc.perform(post("/api/scan/watchlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.stocks[0].symbol").value("AAPL"));
    }




    // -------------------------------------------------------------------------------------- UI

    @Test
    void scanPageRendersTheFormAndWatchlist() throws Exception {
        watchlist.add("VZ", "dividend payer");

        MvcResult result = mockMvc.perform(get("/scan"))
                .andExpect(status().isOk())
                .andExpect(view().name("scan"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("Scan a ticker list")
                .contains("Scan my watchlist")
                .contains("VZ")
                .contains("dividend payer")
                .contains("8 calls/minute");     // the pacing warning is visible up front
    }

    @Test
    void submittingTickersRendersTheTieredTable() throws Exception {
        MvcResult result = mockMvc.perform(post("/scan").param("tickers", "AAPL, THIN, DOWN"))
                .andExpect(status().isOk())
                .andExpect(view().name("scan"))
                .andExpect(model().attributeExists("result"))
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html)
                .contains("Tier 1").contains("Tier 2").contains("Skip")
                .contains("Plan this trade")
                .contains("opens the calculator with the ticker and entry")   // the hint explains it
                .contains("title=\"Size a trade on AAPL")                     // and so does the tooltip
                .contains("below the 50-EMA");

        ScanResult scan = (ScanResult) result.getModelAndView().getModel().get("result");
        assertThat(scan.count(Tier.TIER1)).isEqualTo(1);
        assertThat(scan.tradeable()).hasSize(2);
    }

    @Test
    @DisplayName("only tradeable tiers get a plan link — a SKIP row has no action")
    void skipRowsHaveNoPlanAction() throws Exception {
        MvcResult result = mockMvc.perform(post("/scan").param("tickers", "DOWN")).andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("Plan this trade");
    }

    @Test
    void anEmptyPasteIsRejectedWithAMessage() throws Exception {
        mockMvc.perform(post("/scan").param("tickers", "   "))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attributeDoesNotExist("result"));
    }

    @Test
    void scanningAnEmptyWatchlistSaysSo() throws Exception {
        mockMvc.perform(post("/scan/watchlist"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("error",
                        org.hamcrest.Matchers.containsString("watchlist is empty")));
    }

    @Test
    void watchlistCanBeManagedFromTheUi() throws Exception {
        mockMvc.perform(post("/watchlist").param("ticker", "vz").param("note", "n"))
                .andExpect(status().is3xxRedirection());
        assertThat(watchlist.tickers()).containsExactly("VZ");

        long id = watchlist.findAll().get(0).getId();
        mockMvc.perform(post("/watchlist/" + id + "/delete"))
                .andExpect(status().is3xxRedirection());
        assertThat(watchlist.findAll()).isEmpty();
    }

    @Test
    void aBlankTickerFromTheUiFlashesAnError() throws Exception {
        mockMvc.perform(post("/watchlist").param("ticker", "  "))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attributeExists("error"));
    }

    /** Task 4.4 — entry comes from the scan; stop and target stay blank on purpose. */
    @Test
    void planThisTradePrefillsEntryButNotStopOrTarget() throws Exception {
        MvcResult result = mockMvc.perform(get("/plan").param("ticker", "aapl").param("entry", "40.00"))
                .andExpect(status().isOk())
                .andExpect(view().name("calculator"))
                .andExpect(model().attribute("prefilled", true))
                .andReturn();

        com.swingscope.web.TradeSetupForm form =
                (com.swingscope.web.TradeSetupForm) result.getModelAndView().getModel().get("form");
        assertThat(form.getTicker()).isEqualTo("AAPL");
        assertThat(form.getEntry()).isEqualByComparingTo("40.00");
        assertThat(form.getStop()).isNull();
        assertThat(form.getTarget()).isNull();
        assertThat(form.getAccountSize()).isEqualByComparingTo("500");
        assertThat(form.getRiskAmount()).isEqualByComparingTo("5.00");

        assertThat(result.getResponse().getContentAsString())
                .contains("pre-filled from the scan")
                .contains("no API can give you");
    }

    @Test
    @DisplayName("watchlist writes live on the UI only — the JSON write endpoints are gone")
    void watchlistWriteApiIsNotExposed() throws Exception {
        mockMvc.perform(post("/api/watchlist").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticker\":\"VZ\"}"))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/watchlist/1"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("the note endpoint stays — there is no UI editor for notes yet")
    void noteEndpointStillWorks() throws Exception {
        long id = watchlist.add("VZ", "old note").getId();

        mockMvc.perform(post("/api/watchlist/" + id + "/note")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"dividend payer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("dividend payer"));
    }

    @Test
    void watchlistIsReadableAsJson() throws Exception {
        watchlist.add("VZ", null);

        mockMvc.perform(get("/api/watchlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("VZ"));
    }
}
