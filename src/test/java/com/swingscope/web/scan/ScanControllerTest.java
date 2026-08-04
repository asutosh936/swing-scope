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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScanControllerTest {

    private final MockMvc mockMvc;

    @Autowired
    private WatchlistService watchlist;

    @Autowired
    private com.swingscope.service.scan.ScanJobService scanJobs;

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
    @DisplayName("the JSON scan carries the same analysis the page shows — the two must not drift")
    void scanApiIncludesTheAutoAnalysis() throws Exception {
        mockMvc.perform(post("/api/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"raw\":\"AAPL DOWN\"}"))
                .andExpect(status().isOk())
                // AAPL is Tier 1 and so analysed; DOWN is a Skip and so is not a candidate at all.
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$.candidates[0].verdict").exists())
                .andExpect(jsonPath("$.candidates[0].grade").exists())
                // and never a probability, on this surface either
                .andExpect(jsonPath("$.candidates[0].probability").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].winRate").doesNotExist());
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

    /**
     * Starts a scan and waits for the background worker, then returns the finished results page.
     * Scans cannot be synchronous — a 20-name list takes about five minutes of paced fetching.
     */
    private MvcResult runScanAndOpenResults(String tickers) throws Exception {
        String redirect = mockMvc.perform(post("/scan").param("tickers", tickers))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(redirect).startsWith("/scan/");
        String jobId = redirect.substring("/scan/".length());

        awaitCompletion(jobId);
        return mockMvc.perform(get("/scan/" + jobId)).andExpect(status().isOk()).andReturn();
    }

    private void awaitCompletion(String jobId) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (scanJobs.find(jobId).filter(j -> !j.isRunning()).isPresent()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("scan job " + jobId + " did not finish within 10s");
    }

    @Test
    void submittingTickersRunsAScanAndRendersTheTieredTable() throws Exception {
        MvcResult result = runScanAndOpenResults("AAPL, THIN, DOWN");

        String html = result.getResponse().getContentAsString();
        assertThat(html)
                .contains("Tier 1").contains("Tier 2").contains("Skip")
                .contains("below the 50-EMA");

        ScanResult scan = (ScanResult) result.getModelAndView().getModel().get("result");
        assertThat(scan.count(Tier.TIER1)).isEqualTo(1);
        assertThat(scan.tradeable()).hasSize(2);
    }

    @Test
    @DisplayName("Phase 8: tradeable rows are analysed and sized without a second click")
    void tradeableRowsCarryTheirOwnAnalysis() throws Exception {
        MvcResult result = runScanAndOpenResults("AAPL, THIN, DOWN");

        String html = result.getResponse().getContentAsString();
        assertThat(html)
                // the columns the auto-analysis adds
                .contains("R:R").contains("Shares").contains("Confidence").contains("Verdict")
                // the account the share counts assume — a count means nothing without it
                .contains("account risking")
                // and the standing caveat, on the page rather than only in the plan document
                .contains("not</strong> that the method is proven");

        ScanResult scan = (ScanResult) result.getModelAndView().getModel().get("result");
        assertThat(scan.candidatesFor(Tier.TIER1)).hasSize(1);
        assertThat(scan.candidatesFor(Tier.SKIP)).isEmpty();
    }

    @Test
    @DisplayName("no candles means the row says so and asks for the levels — it does not invent them")
    void refusedLevelsRenderTheirReason() throws Exception {
        // getDailyCandles is unstubbed here, so the level engine has nothing to work from.
        MvcResult result = runScanAndOpenResults("AAPL");

        String html = result.getResponse().getContentAsString();
        assertThat(html)
                .contains("Needs your levels")
                .contains("Not planned automatically")
                .contains("bars of history")      // the engine's own refusal reason, verbatim
                .contains("Add levels");   // and the action that hands it to the human

        ScanResult scan = (ScanResult) result.getModelAndView().getModel().get("result");
        assertThat(scan.needsLevelsCount()).isEqualTo(1);
        assertThat(scan.passCount()).isZero();
    }

    @Test
    @DisplayName("only tradeable tiers get a plan link — a SKIP row has no action")
    void skipRowsHaveNoPlanAction() throws Exception {
        MvcResult result = runScanAndOpenResults("DOWN");

        assertThat(result.getResponse().getContentAsString()).doesNotContain("Plan this trade");
    }

    @Test
    void anEmptyPasteIsRejectedWithAMessage() throws Exception {
        mockMvc.perform(post("/scan").param("tickers", "   "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/scan"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    void scanningAnEmptyWatchlistSaysSo() throws Exception {
        mockMvc.perform(post("/scan/watchlist"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error",
                        org.hamcrest.Matchers.containsString("watchlist is empty")));
    }

    @Test
    @DisplayName("results survive navigation — the same URL re-renders without re-running the scan")
    void resultsStayAddressableAfterClickingAway() throws Exception {
        String redirect = mockMvc.perform(post("/scan").param("tickers", "AAPL"))
                .andReturn().getResponse().getRedirectedUrl();
        String jobId = redirect.substring("/scan/".length());
        awaitCompletion(jobId);

        // Click into the calculator, then come back to exactly the same URL.
        mockMvc.perform(get("/plan").param("ticker", "AAPL").param("entry", "40.00")
                        .param("scanId", jobId).param("suggestLevels", "false"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("scanId", jobId))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Back to scan results")));

        mockMvc.perform(get("/scan/" + jobId))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("result"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AAPL")));
    }

    @Test
    @DisplayName("a plan link carries the scan id, so the trip back is one click")
    void planLinksCarryTheScanId() throws Exception {
        MvcResult result = runScanAndOpenResults("AAPL");

        assertThat(result.getResponse().getContentAsString()).contains("scanId=");
    }

    @Test
    void anUnknownScanIdExplainsItselfRatherThan404() throws Exception {
        mockMvc.perform(get("/scan/nosuchjob"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("error",
                        org.hamcrest.Matchers.containsString("no longer available")))
                .andExpect(model().attributeDoesNotExist("result"));
    }

    @Test
    @DisplayName("recent scans are listed so an older list is never lost")
    void recentScansAreListed() throws Exception {
        String redirect = mockMvc.perform(post("/scan").param("tickers", "AAPL"))
                .andReturn().getResponse().getRedirectedUrl();
        awaitCompletion(redirect.substring("/scan/".length()));

        mockMvc.perform(get("/scan"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("recentScans"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Recent scans")));
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

    /**
     * Task 4.4 + 6.7 — entry comes from the scan. Stop and target are now *proposed* from price
     * structure, so this asserts the opt-out path still leaves them blank: `suggestLevels=false`
     * is the "let me read the chart myself" route, and it must keep working.
     */
    @Test
    void planThisTradeWithoutSuggestionsLeavesStopAndTargetBlank() throws Exception {
        MvcResult result = mockMvc.perform(get("/plan")
                        .param("ticker", "aapl").param("entry", "40.00")
                        .param("suggestLevels", "false"))
                .andExpect(status().isOk())
                .andExpect(view().name("calculator"))
                .andExpect(model().attribute("prefilled", true))
                .andExpect(model().attributeDoesNotExist("levels"))
                .andReturn();

        com.swingscope.web.TradeSetupForm form =
                (com.swingscope.web.TradeSetupForm) result.getModelAndView().getModel().get("form");
        assertThat(form.getTicker()).isEqualTo("AAPL");
        assertThat(form.getEntry()).isEqualByComparingTo("40.00");
        assertThat(form.getStop()).isNull();
        assertThat(form.getTarget()).isNull();
        assertThat(form.getSuggestedStop()).isNull();
        assertThat(form.getSuggestedTarget()).isNull();
        assertThat(form.getAccountSize()).isEqualByComparingTo("500");
        assertThat(form.getRiskAmount()).isEqualByComparingTo("5.00");

        assertThat(result.getResponse().getContentAsString())
                .contains("pre-filled from the scan")
                .contains("no API can give you");
    }

    /**
     * With suggestions on but no candle data behind the mock, the engine must **refuse** rather
     * than invent levels — and the page must still render and stay usable.
     */
    @Test
    void planThisTradeRefusesLevelsWhenThereIsNoCandleData() throws Exception {
        MvcResult result = mockMvc.perform(get("/plan").param("ticker", "aapl").param("entry", "40.00"))
                .andExpect(status().isOk())
                .andExpect(view().name("calculator"))
                .andExpect(model().attributeExists("levels"))
                .andReturn();

        com.swingscope.domain.levels.LevelAnalysis levels =
                (com.swingscope.domain.levels.LevelAnalysis) result.getModelAndView().getModel().get("levels");
        assertThat(levels.stop().isPresent()).isFalse();
        assertThat(levels.target().isPresent()).isFalse();

        com.swingscope.web.TradeSetupForm form =
                (com.swingscope.web.TradeSetupForm) result.getModelAndView().getModel().get("form");
        assertThat(form.getStop()).as("a refusal must not prefill anything").isNull();
        assertThat(form.getSuggestedStop()).isNull();
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
