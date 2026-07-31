package com.swingscope.web.scan;

import com.swingscope.config.MarketDataProperties;
import com.swingscope.config.TradingRules;
import com.swingscope.domain.scan.ScanJob;
import com.swingscope.domain.levels.LevelAnalysis;
import com.swingscope.service.levels.LevelChartRenderer;
import com.swingscope.service.levels.LevelSuggestionService;
import com.swingscope.service.marketdata.MarketDataException;
import com.swingscope.service.marketdata.MarketDataService;
import com.swingscope.service.scan.ScanJobService;
import com.swingscope.service.scan.TierService;
import com.swingscope.service.scan.WatchlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.swingscope.config.OpenApiConfig;
import com.swingscope.domain.scan.ScanResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import com.swingscope.domain.scan.WatchlistEntry;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * Everything scan- and watchlist-related: the pages, and the handful of JSON endpoints worth
 * scripting.
 *
 * <p>The UI posts forms and gets HTML; the {@code /api/**} methods here return JSON. They are the
 * <em>read and compute</em> operations only — mutating the watchlist happens through the UI, so
 * there is one write path rather than two to keep in step.
 */
@Controller
public class ScanController {

    private static final Logger log = LoggerFactory.getLogger(ScanController.class);

    private final TierService tierService;
    private final WatchlistService watchlist;
    private final TradingRules rules;
    private final LevelSuggestionService levelSuggestions;
    private final LevelChartRenderer chartRenderer;
    private final MarketDataService marketData;
    private final ScanJobService scanJobs;
    private final int twelveDataCallsPerMinute;

    public ScanController(TierService tierService, WatchlistService watchlist, TradingRules rules,
                          LevelSuggestionService levelSuggestions, LevelChartRenderer chartRenderer,
                          MarketDataService marketData, ScanJobService scanJobs,
                          MarketDataProperties marketDataProperties) {
        this.tierService = tierService;
        this.watchlist = watchlist;
        this.rules = rules;
        this.levelSuggestions = levelSuggestions;
        this.chartRenderer = chartRenderer;
        this.marketData = marketData;
        this.scanJobs = scanJobs;
        this.twelveDataCallsPerMinute = marketDataProperties.twelvedata().requestsPerMinute();
    }

    @GetMapping("/scan")
    public String form(Model model) {
        model.addAttribute("watchlist", watchlist.findAll());
        model.addAttribute("recentScans", scanJobs.recent());
        return "scan";
    }

    /**
     * Starts a scan and redirects to its own page. Deliberately does <em>not</em> wait: pacing 20
     * tickers past the free tier's 8 calls a minute takes about five minutes, which no browser or
     * proxy will sit through.
     */
    @PostMapping("/scan")
    public String scan(@RequestParam(required = false) String tickers, RedirectAttributes redirect) {
        List<String> parsed = TierService.parseTickers(tickers);
        if (parsed.isEmpty()) {
            redirect.addFlashAttribute("error", "Paste at least one ticker.");
            return "redirect:/scan";
        }
        ScanJob job = scanJobs.submit(parsed);
        log.info("Scan job {} started for {} pasted ticker(s)", job.getId(), parsed.size());
        return "redirect:/scan/" + job.getId();
    }

    @PostMapping("/scan/watchlist")
    public String scanWatchlist(RedirectAttributes redirect) {
        List<String> tickers = watchlist.tickers();
        if (tickers.isEmpty()) {
            redirect.addFlashAttribute("error", "Your watchlist is empty — add a ticker first.");
            return "redirect:/scan";
        }
        ScanJob job = scanJobs.submit(tickers);
        log.info("Scan job {} started for the {}-name watchlist", job.getId(), tickers.size());
        return "redirect:/scan/" + job.getId();
    }

    /**
     * A scan's own page — progress while it runs, results once it finishes, and it stays valid
     * afterwards so clicking through to the calculator and back costs nothing.
     */
    @GetMapping("/scan/{jobId}")
    public String scanJob(@PathVariable String jobId, Model model) {
        ScanJob job = scanJobs.find(jobId).orElse(null);
        if (job == null) {
            model.addAttribute("error",
                    "That scan is no longer available — only the last " + ScanJobService.MAX_RETAINED_JOBS
                            + " are kept, and they are cleared on restart. Run it again.");
            model.addAttribute("watchlist", watchlist.findAll());
            model.addAttribute("recentScans", scanJobs.recent());
            return "scan";
        }

        model.addAttribute("job", job);
        model.addAttribute("result", job.getResult());
        model.addAttribute("pasted", String.join(", ", job.getTickers()));
        model.addAttribute("watchlist", watchlist.findAll());
        model.addAttribute("recentScans", scanJobs.recent());
        // Rough, from the pacing rate: 2 Twelve Data calls per ticker at the configured ceiling.
        model.addAttribute("secondsRemaining",
                job.getEstimatedSecondsRemaining(2, twelveDataCallsPerMinute));
        return "scan";
    }

    // ------------------------------------------------------------------------------- watchlist

    @PostMapping("/watchlist")
    public String addToWatchlist(@RequestParam String ticker,
                                 @RequestParam(required = false) String note,
                                 RedirectAttributes redirect) {
        try {
            watchlist.add(ticker, note);
            redirect.addFlashAttribute("flash", ticker.toUpperCase() + " added to your watchlist.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/scan";
    }

    @PostMapping("/watchlist/{id}/delete")
    public String removeFromWatchlist(@PathVariable Long id, RedirectAttributes redirect) {
        watchlist.remove(id);
        redirect.addFlashAttribute("flash", "Removed from your watchlist.");
        return "redirect:/scan";
    }

    /**
     * Task 4.4 — hands a scanned candidate to the calculator with <strong>entry pre-filled from the
     * current price</strong>. Stop and target stay deliberately blank: no API supplies support and
     * resistance, and reading those two levels off the chart is the judgment this tool keeps human.
     */
    @GetMapping("/plan")
    public String planTrade(@RequestParam String ticker,
                            @RequestParam(required = false) java.math.BigDecimal entry,
                            @RequestParam(defaultValue = "true") boolean suggestLevels,
                            @RequestParam(required = false) String scanId,
                            Model model) {
        String symbol = ticker == null ? null : ticker.trim().toUpperCase();
        log.info("Pre-filling the calculator for {} at {} (suggestLevels={})", symbol, entry, suggestLevels);

        com.swingscope.web.TradeSetupForm form = new com.swingscope.web.TradeSetupForm();
        form.setTicker(symbol);
        form.setEntry(entry);
        form.setAccountSize(rules.defaultAccountSize());
        form.setRiskAmount(rules.defaultRiskAmount());

        if (suggestLevels && symbol != null) {
            // Phase 6: propose stop and target from structure. A failure here must never block the
            // calculator — the human can always type the two numbers, which is the Phase 1-5 flow.
            try {
                LevelAnalysis analysis = levelSuggestions.suggest(symbol);
                if (analysis.stop().isPresent()) {
                    form.setStop(analysis.stop().value());
                    form.setSuggestedStop(analysis.stop().value());
                }
                if (analysis.target().isPresent()) {
                    form.setTarget(analysis.target().value());
                    form.setSuggestedTarget(analysis.target().value());
                }
                model.addAttribute("levels", analysis);

                // Cached from the suggestion call above, so this is free. A chart is only worth
                // drawing when there are bars behind it.
                com.swingscope.domain.marketdata.Candles candles =
                        marketData.getDailyCandles(symbol, 250);
                if (candles != null && !candles.isEmpty()) {
                    model.addAttribute("levelChart", chartRenderer.render(analysis, candles.bars()));
                }
            } catch (MarketDataException e) {
                log.warn("No level suggestions for {} — {}", symbol, e.getMessage());
                model.addAttribute("levelError",
                        "Could not compute levels (" + e.getMessage() + "). Set stop and target yourself.");
            }
        }

        model.addAttribute("form", form);
        model.addAttribute("prefilled", true);
        // So the calculator can offer a way back to the list this trade came from.
        model.addAttribute("scanId", scanId);
        return "calculator";
    }

    // ------------------------------------------------------------------- JSON, for scripting

    /** Accepts either a parsed list or one pasted blob. */
    @Schema(description = "Supply either `tickers` (a parsed array) or `raw` (one pasted blob). "
            + "If both are present, `tickers` wins.")
    public record ScanRequest(
            @Schema(description = "Explicit ticker list.", example = "[\"AAPL\",\"MSFT\"]")
            List<String> tickers,
            @Schema(description = "Pasted blob; splits on commas, semicolons, spaces and newlines.",
                    example = "VZ, CARR AAPL\nMSFT")
            String raw) {
    }

    @Schema(description = "New note text; null or blank clears it.")
    public record NoteRequest(
            @Schema(example = "dividend payer, slow mover") String note) {
    }

    @Operation(
            summary = "Tier a ticker list against the mechanical filters",
            description = """
                    Paste the output of your screener; this fetches data for each name and sorts them                     into tiers. It tells you **what is worth your chart time**, never what to buy.

                    Tiers, in the order the rules are applied:
                    * `SKIP` — failed the trend test (below the 50-EMA, or EMA50 below EMA200), or too                     little history to judge it
                    * `TIER3` — trend intact but event risk today: earnings inside 3 days, or a move                     over ±5%
                    * `TIER1` — trend intact, **average** daily volume over the threshold AND market                     cap over it too
                    * `TIER2` — trend intact but thinner or smaller than Tier 1 requires
                    * `UNAVAILABLE` — data could not be fetched. This is a statement about the fetch,                     not about the stock

                    Liquidity is judged on **average** daily volume, not today's running total, which                     is only a partial session while the market is open.

                    **This can be slow.** The upstream free tier allows 8 calls a minute and each name                     needs two to four, so a cold 20-ticker scan takes a few minutes; the call blocks                     until it finishes. Repeat scans come from cache and return immediately. Names that                     fail the trend test short-circuit and skip their fundamentals lookups.

                    A single bad ticker never fails the whole scan — it comes back as `UNAVAILABLE`.
                    """,
            tags = OpenApiConfig.TAG_SCAN)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Tiered results, plus `elapsedMillis` and any `warnings` such as "
                            + "duplicates dropped or the list being truncated to the per-scan limit.",
                    content = @Content(schema = @Schema(implementation = ScanResult.class))),
            @ApiResponse(responseCode = "429",
                    description = "Provider budget exhausted mid-scan. Carries `Retry-After`.",
                    content = @Content),
            @ApiResponse(responseCode = "503", description = "No market-data provider configured.",
                    content = @Content)
    })
    @PostMapping("/api/scan")
    @ResponseBody
    public ScanResult scanApi(@RequestBody ScanRequest request) {
        List<String> tickers = request.tickers() != null && !request.tickers().isEmpty()
                ? request.tickers()
                : TierService.parseTickers(request.raw());
        log.info("POST /api/scan with {} ticker(s)", tickers.size());
        return tierService.scan(tickers);
    }

    /** Scan the saved watchlist without pasting anything. */
    @Operation(
            summary = "Tier the saved watchlist",
            description = "Same filters as `/api/scan`, run over the names you have saved, so there is "
                    + "nothing to paste. No request body.",
            tags = OpenApiConfig.TAG_SCAN)
    @ApiResponse(responseCode = "200", description = "Tiered results for the saved names.",
            content = @Content(schema = @Schema(implementation = ScanResult.class)))
    @PostMapping("/api/scan/watchlist")
    @ResponseBody
    public ScanResult scanWatchlistApi() {
        List<String> tickers = watchlist.tickers();
        log.info("POST /api/scan/watchlist — {} saved ticker(s)", tickers.size());
        return tierService.scan(tickers);
    }

    @Operation(
            summary = "The saved watchlist",
            description = "The stable set of names you track each session. Adding and removing happens "
                    + "on the scan page — this endpoint is read-only.",
            tags = OpenApiConfig.TAG_SCAN)
    @ApiResponse(responseCode = "200", description = "Saved tickers, with notes and the date added.",
            content = @Content(array = @io.swagger.v3.oas.annotations.media.ArraySchema(
                    schema = @Schema(implementation = WatchlistEntry.class))))
    @GetMapping("/api/watchlist")
    @ResponseBody
    public List<WatchlistEntry> watchlistApi() {
        return watchlist.findAll();
    }

    /** Notes have no UI editor yet, so this one write stays on the API. */
    @Operation(
            summary = "Edit a watchlist note",
            description = "The one write left on this API: there is no note editor in the UI yet. "
                    + "Adding and removing tickers happens on the scan page.",
            tags = OpenApiConfig.TAG_SCAN)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated entry.",
                    content = @Content(schema = @Schema(implementation = WatchlistEntry.class))),
            @ApiResponse(responseCode = "404", description = "No watchlist entry with that id.",
                    content = @Content(examples = @ExampleObject(value = """
                            {"timestamp":"2026-07-29T15:14:35Z","status":404,
                             "message":"no watchlist entry with id 9999","provider":"watchlist"}""")))
    })
    @PostMapping("/api/watchlist/{id}/note")
    @ResponseBody
    public WatchlistEntry updateNoteApi(
            @Parameter(description = "Watchlist entry id.", example = "1") @PathVariable Long id,
            @RequestBody NoteRequest request) {
        log.info("POST /api/watchlist/{}/note", id);
        return watchlist.updateNote(id, request.note());
    }
}
