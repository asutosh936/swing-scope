package com.swingscope.web;

import com.swingscope.config.OpenApiConfig;
import com.swingscope.domain.backtest.BacktestSettings;
import com.swingscope.domain.marketdata.Candle;
import com.swingscope.service.levels.ParameterSweep;
import com.swingscope.service.marketdata.MarketDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the parameter sweep over real history (6A.7).
 *
 * <p>The caveats are returned <strong>in the response body</strong>, not left to documentation. A
 * backtest result read without its limitations is worse than no result, because it carries false
 * authority — so anything that consumes this endpoint gets the warnings whether it wants them or not.
 */
@RestController
@RequestMapping("/api/backtest")
@Tag(name = OpenApiConfig.TAG_BACKTEST)
public class BacktestController {

    private static final Logger log = LoggerFactory.getLogger(BacktestController.class);

    /** Each symbol costs one provider call, paced at the free-tier ceiling. */
    private static final int MAX_SYMBOLS = 10;
    private static final int DEFAULT_BARS = 2000;
    private static final int MAX_BARS = 5000;

    private final MarketDataService marketData;
    private final ParameterSweep sweep;

    public BacktestController(MarketDataService marketData, ParameterSweep sweep) {
        this.marketData = marketData;
        this.sweep = sweep;
    }

    public record BacktestRequest(List<String> symbols, Integer bars, BigDecimal inSampleFraction,
                                  Integer timeStopBars) {
    }

    /** One parameter set, flattened for reading. */
    public record ResultRow(
            String label,
            boolean baseline,
            BigDecimal outOfSampleExpectancyR,
            BigDecimal inSampleExpectancyR,
            BigDecimal degradationR,
            int outOfSampleTrades,
            int outOfSampleNonOverlapping,
            BigDecimal hitRatePercent,
            BigDecimal worstR,
            BigDecimal ambiguousRatePercent,
            boolean beatsBaseline,
            boolean conclusive,
            boolean adoptable
    ) {
    }

    public record BacktestResponse(
            List<String> symbols,
            int barsPerSymbol,
            BigDecimal inSampleFraction,
            int timeStopBars,
            List<ResultRow> results,
            String verdict,
            List<String> caveats
    ) {
    }

    @Operation(
            summary = "Sweep level parameters over real history and rank them",
            description = """
                    Replays the level engine bar by bar across the requested symbols, at every \
                    combination in the parameter grid, and ranks the results by **out-of-sample** \
                    expectancy in R.

                    **This can take minutes.** Each symbol costs one provider call, paced at 8/min, \
                    and the replay itself is thousands of level computations per symbol.

                    **How to read the result.** `outOfSampleExpectancyR` is the only number worth \
                    ranking on — `inSampleExpectancyR` is shown for contrast, and a large positive \
                    `degradationR` between them is the signature of overfitting. A set is only \
                    `adoptable` when it is conclusive (enough held-back trades), beats the naive \
                    ATR baseline, and has positive expectancy.

                    **If nothing is adoptable, that is a finding, not a failure** — it means the \
                    structure-based levels did not beat two lines drawn from volatility, and the \
                    honest response is to ship the baseline or keep setting levels by hand.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Ranked results, best out-of-sample first, with caveats attached.",
                    content = @Content(examples = @ExampleObject(value = """
                            {"symbols":["AAPL"],"barsPerSymbol":2000,"verdict":"...",
                             "results":[{"label":"pivot=3 buffer=0.5×ATR touches=2",
                                         "outOfSampleExpectancyR":0.12,"adoptable":true}]}"""))),
            @ApiResponse(responseCode = "400", description = "No symbols, or too many.",
                    content = @Content),
            @ApiResponse(responseCode = "429", description = "Provider rate limit exhausted.",
                    content = @Content),
            @ApiResponse(responseCode = "503", description = "No provider configured for candles.",
                    content = @Content)
    })
    @PostMapping
    public BacktestResponse run(@RequestBody BacktestRequest request) {
        List<String> symbols = normalise(request.symbols());
        int bars = Math.min(request.bars() == null ? DEFAULT_BARS : request.bars(), MAX_BARS);
        BigDecimal inSample = request.inSampleFraction() == null
                ? new BigDecimal("0.70") : request.inSampleFraction();
        int timeStop = request.timeStopBars() == null
                ? BacktestSettings.DEFAULT_TIME_STOP_BARS : request.timeStopBars();

        log.info("Backtest sweep starting: {} symbol(s), {} bars each, in-sample {}, timeStop {}",
                symbols.size(), bars, inSample, timeStop);
        long startedAt = System.nanoTime();

        Map<String, List<Candle>> barsBySymbol = new LinkedHashMap<>();
        for (String symbol : symbols) {
            barsBySymbol.put(symbol, marketData.getDailyCandles(symbol, bars).bars());
        }

        BacktestSettings settings = new BacktestSettings(BacktestSettings.EntryRule.NEXT_OPEN, timeStop);
        List<ParameterSweep.Result> ranked = sweep.sweepAcross(barsBySymbol,
                ParameterSweep.Grid.defaults(), settings, inSample);

        List<ResultRow> rows = ranked.stream().map(BacktestController::toRow).toList();
        String verdict = verdictFor(rows);

        log.info("Backtest sweep finished in {}s — {}",
                (System.nanoTime() - startedAt) / 1_000_000_000, verdict);

        return new BacktestResponse(symbols, bars, inSample, timeStop, rows, verdict, caveats());
    }

    private static ResultRow toRow(ParameterSweep.Result r) {
        return new ResultRow(r.label(), r.baseline(),
                r.outOfSampleExpectancy(), r.inSampleExpectancy(), r.report().degradationR(),
                r.report().outOfSample().resolvedTrades(),
                r.report().outOfSample().nonOverlappingEstimate(),
                r.report().outOfSample().hitRate(), r.report().outOfSample().worstR(),
                r.report().outOfSample().ambiguousRate(),
                r.beatsBaseline(), r.conclusive(), r.adoptable());
    }

    private static String verdictFor(List<ResultRow> rows) {
        List<ResultRow> adoptable = rows.stream().filter(ResultRow::adoptable).toList();
        if (adoptable.isEmpty()) {
            boolean anyConclusive = rows.stream().anyMatch(ResultRow::conclusive);
            return anyConclusive
                    ? "NOTHING ADOPTABLE — no parameter set beat the naive ATR baseline out-of-sample. "
                            + "That is the finding: ship the baseline, or keep setting levels by hand."
                    : "INCONCLUSIVE — too few held-back trades to judge any set. Use more history "
                            + "or more symbols before drawing a conclusion.";
        }
        return "%d set(s) cleared all three gates. Best: %s at %sR out-of-sample."
                .formatted(adoptable.size(), adoptable.get(0).label(),
                        adoptable.get(0).outOfSampleExpectancyR());
    }

    /** Returned with every response — a result read without these carries false authority. */
    private static List<String> caveats() {
        return List.of(
                "Ranked on out-of-sample expectancy only; in-sample is shown for contrast and must "
                        + "never be used to choose a parameter set.",
                "Entries overlap heavily — consecutive bars produce near-identical setups. Read "
                        + "outOfSampleNonOverlapping, not outOfSampleTrades.",
                "Intrabar ambiguity is scored as a loss and gaps fill at the open, so results are "
                        + "deliberately pessimistic rather than flattering.",
                "Survivorship and selection bias are NOT corrected: these are symbols that still "
                        + "exist and that you chose.",
                "Past behaviour of a level-placement rule is not a forecast. This measures whether "
                        + "the rule would have worked, not whether it will.");
    }

    private static List<String> normalise(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            throw new IllegalArgumentException("at least one symbol is required");
        }
        List<String> cleaned = new ArrayList<>(new java.util.LinkedHashSet<>(symbols.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toUpperCase())
                .toList()));
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("at least one symbol is required");
        }
        if (cleaned.size() > MAX_SYMBOLS) {
            throw new IllegalArgumentException(
                    "at most %d symbols per run — each costs a paced provider call".formatted(MAX_SYMBOLS));
        }
        return cleaned;
    }
}
