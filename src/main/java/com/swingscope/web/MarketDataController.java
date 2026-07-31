package com.swingscope.web;

import com.swingscope.config.OpenApiConfig;
import com.swingscope.domain.marketdata.MarketSnapshot;
import com.swingscope.domain.marketdata.MarketStatus;
import com.swingscope.domain.levels.LevelAnalysis;
import com.swingscope.domain.marketdata.SymbolMatch;
import com.swingscope.service.levels.LevelSuggestionService;
import com.swingscope.service.marketdata.MarketDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only market data. Nothing here places an order or recommends one. */
@RestController
@RequestMapping("/api/marketdata")
@Tag(name = OpenApiConfig.TAG_MARKET_DATA)
public class MarketDataController {

    private static final Logger log = LoggerFactory.getLogger(MarketDataController.class);

    private final MarketDataService marketData;
    private final LevelSuggestionService levels;

    public MarketDataController(MarketDataService marketData, LevelSuggestionService levels) {
        this.marketData = marketData;
        this.levels = levels;
    }

    @Operation(
            summary = "Suggested stop and target levels for one symbol",
            description = """
                    Computes support and resistance **zones** from the daily candles already cached                     for this symbol, then proposes a stop and a target. Costs no extra provider call                     when the symbol has been scanned recently.

                    * **Stop** = nearest support zone below, minus `stopBuffer × ATR` — below the                     shelf rather than at it, so ordinary noise does not trigger it.
                    * **Target** = the *near edge* of the nearest resistance above: take profit into                     resistance rather than through it.

                    **A refusal is a real answer.** When there is no clean structure — too little                     history, no confirmed pivot, or a stop wider than the configured limit — the                     `value` is null and `rationale` says why. The service will not fall back to an                     arbitrary number dressed as a formula.

                    Every figure is arithmetic over past price. It is **not** a recommendation to                     trade, and `unconfirmedTailBars` tells you how many recent bars are still too                     new to have formed a pivot.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Analysis complete. `stop`/`target` may each be a refusal.",
                    content = @Content(schema = @Schema(implementation = LevelAnalysis.class))),
            @ApiResponse(responseCode = "404", description = "No provider has data for this ticker.",
                    content = @Content),
            @ApiResponse(responseCode = "429", description = "Provider rate limit exhausted.",
                    content = @Content),
            @ApiResponse(responseCode = "503", description = "No provider configured for candles.",
                    content = @Content)
    })
    @GetMapping("/{symbol}/levels")
    public ResponseEntity<LevelAnalysis> levels(
            @Parameter(description = "Ticker symbol; case-insensitive.", example = "AAPL")
            @PathVariable String symbol) {
        log.info("GET /api/marketdata/{}/levels", symbol);
        return ResponseEntity.ok(levels.suggest(symbol));
    }

    @Operation(
            summary = "Combined data snapshot for one symbol",
            description = """
                    Everything the mechanical filters need, assembled across providers: last price and \
                    change%, EMA20/50/200, today's and average volume, market cap, and the next \
                    earnings date.

                    **EMAs are computed in-app** from ~250 daily closes rather than taken from a \
                    provider's indicator endpoint, so they match a standard chart: seeded with the SMA \
                    of the first N closes, then `close × k + prev × (1−k)` with `k = 2/(N+1)`.

                    Three booleans restate the plan's rules as arithmetic, not advice:
                    * `inUptrend` — price > EMA50 AND EMA50 > EMA200. **`null` means fewer than 200 \
                    bars were available, so the test is inconclusive** — do not read null as false.
                    * `bigMover` — |change%| > 5, i.e. news risk.
                    * `earningsWithin3Days` — the earnings block window.

                    Price and candles are required; if market cap or earnings can't be fetched the \
                    snapshot still returns with a note in `warnings`. Support, resistance and the \
                    trigger candle are never included — reading those off the chart is the judgment \
                    this tool deliberately leaves to you.

                    `marketCap` is in **millions** of USD, as the provider reports it.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Snapshot assembled. May carry `warnings`.",
                    content = @Content(schema = @Schema(implementation = MarketSnapshot.class))),
            @ApiResponse(responseCode = "404", description = "No provider has data for this ticker.",
                    content = @Content(examples = @ExampleObject(value = """
                            {"timestamp":"2026-07-29T15:14:35Z","status":404,
                             "message":"unknown symbol 'NOPE' at twelvedata","provider":"twelvedata"}"""))),
            @ApiResponse(responseCode = "429",
                    description = "Provider rate limit exhausted after retries. Carries `Retry-After`.",
                    content = @Content),
            @ApiResponse(responseCode = "503",
                    description = "No provider is configured for a needed capability — usually a "
                            + "missing API key — or the endpoint requires a paid plan.",
                    content = @Content),
            @ApiResponse(responseCode = "502", description = "Upstream provider failed.",
                    content = @Content)
    })
    @GetMapping("/{symbol}")
    public ResponseEntity<MarketSnapshot> snapshot(
            @Parameter(description = "Ticker symbol; case-insensitive.", example = "AAPL")
            @PathVariable String symbol) {
        log.info("GET /api/marketdata/{}", symbol);
        return ResponseEntity.ok(marketData.getSnapshot(symbol));
    }

    @Operation(
            summary = "Look up tickers by name or symbol",
            description = "Validates a ticker before you scan or journal it. Matches on both the "
                    + "symbol and the instrument name.")
    @ApiResponse(responseCode = "200", description = "Zero or more matches; an empty list is not an error.",
            content = @Content(array = @io.swagger.v3.oas.annotations.media.ArraySchema(
                    schema = @Schema(implementation = SymbolMatch.class))))
    @GetMapping("/search")
    public ResponseEntity<List<SymbolMatch>> search(
            @Parameter(description = "Symbol fragment or company name.", example = "apple")
            @RequestParam("q") String query) {
        log.info("GET /api/marketdata/search?q={}", query);
        return ResponseEntity.ok(marketData.search(query));
    }

    @Operation(
            summary = "Is the US market open",
            description = "Informational only — nothing in the tool gates on it. `session` and "
                    + "`holiday` are both nullable outside trading hours.")
    @ApiResponse(responseCode = "200", description = "Current session state.",
            content = @Content(schema = @Schema(implementation = MarketStatus.class),
                    examples = @ExampleObject(value = """
                            {"exchange":"US","open":false,"session":null,"holiday":null}""")))
    @GetMapping("/status")
    public ResponseEntity<MarketStatus> marketStatus() {
        log.info("GET /api/marketdata/status");
        return ResponseEntity.ok(marketData.getMarketStatus());
    }
}
