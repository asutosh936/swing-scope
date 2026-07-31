package com.swingscope.service.levels;

import com.swingscope.config.LevelProperties;
import com.swingscope.domain.backtest.BacktestOutcome;
import com.swingscope.domain.backtest.BacktestReport;
import com.swingscope.domain.backtest.BacktestSettings;
import com.swingscope.domain.backtest.BacktestTrade;
import com.swingscope.domain.levels.LevelAnalysis;
import com.swingscope.domain.marketdata.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays the level engine over history to find out whether its suggestions were any good.
 *
 * <p>Phase 6 shipped with every threshold set by judgement. This exists to replace those judgements
 * with measurements — and, just as importantly, to be capable of reporting that the suggestions are
 * <em>worse</em> than doing nothing. A harness that can only confirm the feature was worth building
 * is not a measurement.
 *
 * <h2>The one property everything depends on</h2>
 * At entry bar {@code i}, levels are computed from {@code bars[0..i]} and nothing else. There is no
 * flag to get this wrong: the sublist is the only thing handed to
 * {@link LevelSuggestionService#analyse}, which reads no other source. Appending future bars to the
 * input must not alter a single already-computed trade — asserted directly in the tests.
 *
 * <h2>Where it is deliberately pessimistic</h2>
 * <ul>
 *   <li><strong>Intrabar ambiguity resolves as a loss.</strong> When one bar's range spans both the
 *       stop and the target, daily data cannot say which came first. Assuming the favourable order
 *       is exactly how backtests lie to their authors.</li>
 *   <li><strong>Gaps fill at the open, not the level.</strong> A bar opening below the stop loses
 *       more than 1R, and recording it as −1R would hide the worst outcomes the strategy produces.</li>
 * </ul>
 */
@Service
public class LevelBacktestService {

    private static final Logger log = LoggerFactory.getLogger(LevelBacktestService.class);

    private final LevelSuggestionService suggestions;
    private final LevelProperties properties;

    public LevelBacktestService(LevelSuggestionService suggestions, LevelProperties properties) {
        this.suggestions = suggestions;
        this.properties = properties;
    }

    public BacktestReport replay(String symbol, List<Candle> bars) {
        return replay(symbol, bars, BacktestSettings.defaults());
    }

    /**
     * Walks the series forward, proposing a setup at each bar and following it to resolution.
     *
     * @param bars daily candles, oldest first — the full history to replay over
     */
    public BacktestReport replay(String symbol, List<Candle> bars, BacktestSettings settings) {
        List<BacktestTrade> trades = new ArrayList<>();
        if (bars == null || bars.isEmpty()) {
            return BacktestReport.of(symbol, trades, settings.timeStopBars());
        }

        int firstEntry = properties.minBarsForSuggestion();
        // Every entry needs at least one bar afterwards to resolve against.
        int lastEntry = bars.size() - 2;

        for (int i = firstEntry; i <= lastEntry; i++) {
            trades.add(replayOne(symbol, bars, i, settings));
        }

        BacktestReport report = BacktestReport.of(symbol, trades, settings.timeStopBars());
        log.info("Backtest {}: {} entries considered, {} resolved (~{} non-overlapping) — "
                        + "hit rate {}%, expectancy {}R, worst {}R, {}% decided by an ambiguous bar",
                symbol, report.entriesConsidered(), report.resolvedTrades(),
                report.nonOverlappingEstimate(), report.hitRate(), report.expectancyR(),
                report.worstR(), report.ambiguousRate());
        return report;
    }

    /** One entry: compute levels as of bar {@code i}, then walk forward to resolution. */
    BacktestTrade replayOne(String symbol, List<Candle> bars, int i, BacktestSettings settings) {
        Candle signalBar = bars.get(i);

        // THE no-lookahead line: only bars up to and including i are visible.
        List<Candle> asOf = bars.subList(0, i + 1);
        LevelAnalysis analysis = suggestions.analyse(symbol, asOf, signalBar.close());

        if (!analysis.isComplete()) {
            return BacktestTrade.noSuggestion(symbol, i, signalBar.date());
        }

        BigDecimal stop = analysis.stop().value();
        BigDecimal target = analysis.target().value();

        // Where the fill happens, and from which bar the trade is live.
        int firstLiveBar;
        BigDecimal entryPrice;
        if (settings.entryRule() == BacktestSettings.EntryRule.NEXT_OPEN) {
            entryPrice = bars.get(i + 1).open();
            firstLiveBar = i + 1;          // in from that bar's open, so its own range can resolve it
        } else {
            entryPrice = signalBar.close();
            firstLiveBar = i + 1;          // the signal bar has already traded
        }

        BigDecimal risk = entryPrice.subtract(stop);
        if (risk.signum() <= 0 || target.compareTo(entryPrice) <= 0) {
            // Gapped to or through the stop before entry, or through the target. No trade to take,
            // but the proposal itself is kept on the record.
            return BacktestTrade.unresolved(symbol, i, signalBar.date(), entryPrice, stop, target,
                    BacktestOutcome.NOT_TAKEABLE);
        }

        int lastBar = Math.min(firstLiveBar + settings.timeStopBars() - 1, bars.size() - 1);
        for (int j = firstLiveBar; j <= lastBar; j++) {
            Candle bar = bars.get(j);

            // --- gaps first: the open is the only price actually available at that moment
            if (bar.open().compareTo(stop) <= 0) {
                return resolved(symbol, i, signalBar, entryPrice, stop, target, j, bar,
                        bar.open(), BacktestOutcome.STOP_FIRST, risk, false, true);
            }
            if (bar.open().compareTo(target) >= 0) {
                return resolved(symbol, i, signalBar, entryPrice, stop, target, j, bar,
                        bar.open(), BacktestOutcome.TARGET_FIRST, risk, false, true);
            }

            boolean touchedStop = bar.low().compareTo(stop) <= 0;
            boolean touchedTarget = bar.high().compareTo(target) >= 0;

            // --- ambiguity: both inside one bar. Daily data cannot order them, so assume the loss.
            if (touchedStop && touchedTarget) {
                return resolved(symbol, i, signalBar, entryPrice, stop, target, j, bar,
                        stop, BacktestOutcome.STOP_FIRST, risk, true, false);
            }
            if (touchedStop) {
                return resolved(symbol, i, signalBar, entryPrice, stop, target, j, bar,
                        stop, BacktestOutcome.STOP_FIRST, risk, false, false);
            }
            if (touchedTarget) {
                return resolved(symbol, i, signalBar, entryPrice, stop, target, j, bar,
                        target, BacktestOutcome.TARGET_FIRST, risk, false, false);
            }
        }

        // Did the walk actually get its full window, or did the series simply end?
        boolean fullWindowAvailable = lastBar == firstLiveBar + settings.timeStopBars() - 1;
        if (!fullWindowAvailable) {
            // Censored: the outcome is unknown, not a timeout. Scoring it either way would bias
            // the report, and the tail of every series produces these.
            return BacktestTrade.unresolved(symbol, i, signalBar.date(), entryPrice, stop, target,
                    BacktestOutcome.INCOMPLETE);
        }

        // --- time stop: out at the last close, whatever it is
        Candle exitBar = bars.get(lastBar);
        return resolved(symbol, i, signalBar, entryPrice, stop, target, lastBar, exitBar,
                exitBar.close(), BacktestOutcome.TIMEOUT, risk, false, false);
    }

    private static BacktestTrade resolved(String symbol, int entryIndex, Candle signalBar,
                                          BigDecimal entryPrice, BigDecimal stop, BigDecimal target,
                                          int exitIndex, Candle exitBar, BigDecimal exitPrice,
                                          BacktestOutcome outcome, BigDecimal risk,
                                          boolean ambiguous, boolean gapped) {
        BigDecimal r = exitPrice.subtract(entryPrice).divide(risk, 2, RoundingMode.HALF_UP);
        return new BacktestTrade(symbol, entryIndex, signalBar.date(), entryPrice, stop, target,
                exitIndex, exitBar.date(), exitPrice, outcome, r,
                exitIndex - entryIndex, ambiguous, gapped);
    }
}
