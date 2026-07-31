package com.swingscope.service.levels;

import com.swingscope.config.LevelProperties;
import com.swingscope.domain.backtest.BacktestOutcome;
import com.swingscope.domain.backtest.BacktestReport;
import com.swingscope.domain.backtest.BacktestSettings;
import com.swingscope.domain.backtest.BacktestTrade;
import com.swingscope.domain.marketdata.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LevelBacktestServiceTest {

    private final LevelProperties properties =
            new LevelProperties(null, null, null, null, null, null, null, null);
    private final AtrCalculator atr = new AtrCalculator();
    private final PriceLevelService levels =
            new PriceLevelService(new SwingPointDetector(), atr, properties);
    private final LevelSuggestionService suggestions =
            new LevelSuggestionService(null, levels, atr, properties);
    private final LevelBacktestService backtest = new LevelBacktestService(suggestions, properties);

    private static Candle bar(LocalDate date, double open, double high, double low, double close) {
        return new Candle(date, BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), 1_000_000L);
    }

    /** Oscillates between a ~40 support shelf and a ~60 resistance shelf, giving clean pivots. */
    private static List<Candle> oscillating(int cycles) {
        List<Candle> bars = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 1);
        int day = 0;
        for (int c = 0; c < cycles; c++) {
            double[] legDown = {58, 55, 52, 48, 44, 41, 40.2, 41, 44, 48};
            double[] legUp = {52, 55, 58, 59.8, 59, 57, 54, 51, 49, 50};
            for (double p : legDown) {
                bars.add(bar(date.plusDays(day++), p, p + 1, p - 1, p));
            }
            for (double p : legUp) {
                bars.add(bar(date.plusDays(day++), p, p + 1, p - 1, p));
            }
        }
        return bars;
    }

    // ------------------------------------------------------------------ 6A.4 — no lookahead

    @Test
    @DisplayName("appending future bars changes no already-computed trade — the whole harness rests on this")
    void appendingFutureDataCannotAlterEarlierTrades() {
        List<Candle> history = oscillating(12);          // 240 bars
        List<Candle> withFuture = new ArrayList<>(history);
        // A violent future collapse that would create a much lower support shelf if it leaked back.
        LocalDate after = LocalDate.of(2030, 1, 1);
        for (int i = 0; i < 60; i++) {
            withFuture.add(bar(after.plusDays(i), 5, 6, 4, 5));
        }

        BacktestReport shortRun = backtest.replay("TEST", history);
        BacktestReport longRun = backtest.replay("TEST", withFuture);

        assertThat(shortRun.trades()).isNotEmpty();
        int comparedOutcomes = 0;
        for (int i = 0; i < shortRun.trades().size(); i++) {
            BacktestTrade original = shortRun.trades().get(i);
            BacktestTrade rerun = longRun.trades().get(i);

            // The LEVELS must be identical for every entry without exception — that is the
            // no-lookahead property. Future bars cannot change what was knowable at bar i.
            assertThat(rerun.entryBarIndex()).isEqualTo(original.entryBarIndex());
            assertThat(rerun.stop()).isEqualTo(original.stop());
            assertThat(rerun.target()).isEqualTo(original.target());

            // Outcomes are only comparable where the short run actually had room to resolve.
            // Its tail entries are censored (INCOMPLETE); the long run resolves them properly,
            // and that difference is data availability, not leakage.
            if (original.outcome() != BacktestOutcome.INCOMPLETE) {
                assertThat(rerun.outcome()).isEqualTo(original.outcome());
                assertThat(rerun.rMultiple()).isEqualTo(original.rMultiple());
                comparedOutcomes++;
            }
        }
        assertThat(comparedOutcomes)
                .as("the comparison must actually cover resolved trades, not just censored ones")
                .isGreaterThan(50);
    }

    @Test
    @DisplayName("a single entry sees only bars up to and including its own")
    void oneEntryUsesOnlyItsOwnHistory() {
        List<Candle> history = oscillating(12);
        List<Candle> truncated = new ArrayList<>(history.subList(0, 150));

        BacktestTrade fromFull = backtest.replayOne("TEST", history, 120, BacktestSettings.defaults());
        BacktestTrade fromTruncated =
                backtest.replayOne("TEST", truncated, 120, BacktestSettings.defaults());

        // Levels are identical; only the resolution can differ if truncation cut the walk short.
        assertThat(fromTruncated.stop()).isEqualTo(fromFull.stop());
        assertThat(fromTruncated.target()).isEqualTo(fromFull.target());
    }

    // -------------------------------------------------- 6A.3 — deliberately pessimistic resolvers

    @Test
    @DisplayName("a bar containing BOTH levels is scored as a loss — daily data cannot order them")
    void intrabarAmbiguityResolvesAsAStop() {
        List<Candle> bars = new ArrayList<>(oscillating(12));
        int entry = bars.size() - 1;
        LocalDate next = bars.get(entry).date().plusDays(1);
        // Next bar opens between the levels, then its range engulfs both.
        bars.add(bar(next, 50, 90, 1, 50));

        BacktestTrade trade = backtest.replayOne("TEST", bars, entry, BacktestSettings.defaults());

        assertThat(trade.outcome()).isEqualTo(BacktestOutcome.STOP_FIRST);
        assertThat(trade.ambiguousBar()).isTrue();
        assertThat(trade.rMultiple()).isEqualByComparingTo("-1.00");
    }

    @Test
    @DisplayName("a gap through the stop fills at the open and loses MORE than 1R")
    void gapThroughStopIsWorseThanOneR() {
        List<Candle> bars = new ArrayList<>(oscillating(12));
        int entry = bars.size() - 1;
        LocalDate d = bars.get(entry).date();
        bars.add(bar(d.plusDays(1), 50, 51, 49, 50));      // normal fill bar
        bars.add(bar(d.plusDays(2), 20, 21, 19, 20));      // gaps far below any plausible stop

        BacktestTrade trade = backtest.replayOne("TEST", bars, entry, BacktestSettings.defaults());

        assertThat(trade.outcome()).isEqualTo(BacktestOutcome.STOP_FIRST);
        assertThat(trade.gapped()).isTrue();
        assertThat(trade.exitPrice()).isEqualByComparingTo("20");
        assertThat(trade.rMultiple())
                .as("a gap must be recorded as worse than a clean stop")
                .isLessThan(new BigDecimal("-1.00"));
    }

    @Test
    @DisplayName("a clean stop is exactly -1R")
    void aCleanStopIsMinusOneR() {
        List<Candle> bars = new ArrayList<>(oscillating(12));
        int entry = bars.size() - 1;
        LocalDate d = bars.get(entry).date();
        bars.add(bar(d.plusDays(1), 50, 51, 49, 50));
        // Dip to well under the stop but open above it, so the stop price itself is the fill.
        bars.add(bar(d.plusDays(2), 49, 49.5, 30, 40));

        BacktestTrade trade = backtest.replayOne("TEST", bars, entry, BacktestSettings.defaults());

        assertThat(trade.outcome()).isEqualTo(BacktestOutcome.STOP_FIRST);
        assertThat(trade.gapped()).isFalse();
        assertThat(trade.rMultiple()).isEqualByComparingTo("-1.00");
    }

    @Test
    @DisplayName("reaching the target scores positive R")
    void reachingTargetIsPositiveR() {
        List<Candle> bars = new ArrayList<>(oscillating(12));
        int entry = bars.size() - 1;
        LocalDate d = bars.get(entry).date();
        bars.add(bar(d.plusDays(1), 50, 51, 49, 50));
        bars.add(bar(d.plusDays(2), 51, 75, 50.5, 74));    // sails through the target

        BacktestTrade trade = backtest.replayOne("TEST", bars, entry, BacktestSettings.defaults());

        assertThat(trade.outcome()).isEqualTo(BacktestOutcome.TARGET_FIRST);
        assertThat(trade.rMultiple().signum()).isPositive();
    }

    @Test
    @DisplayName("neither level inside the time stop is a TIMEOUT, exited at the last close")
    void timeStopClosesTheTrade() {
        List<Candle> bars = new ArrayList<>(oscillating(12));
        int entry = bars.size() - 1;
        LocalDate d = bars.get(entry).date();
        for (int i = 1; i <= 20; i++) {                     // drifts nowhere for longer than 15 bars
            bars.add(bar(d.plusDays(i), 50, 50.4, 49.6, 50));
        }

        BacktestTrade trade = backtest.replayOne("TEST", bars, entry,
                new BacktestSettings(BacktestSettings.EntryRule.NEXT_OPEN, 15));

        assertThat(trade.outcome()).isEqualTo(BacktestOutcome.TIMEOUT);
        assertThat(trade.barsHeld()).isEqualTo(15);
    }

    // ------------------------------------------------------------------------- not-taken paths

    @Test
    void aRefusedSetupIsNotATrade() {
        List<Candle> tooShort = oscillating(4);            // 80 bars, but a strong uptrend-free series

        BacktestReport report = backtest.replay("TEST", tooShort);

        assertThat(report.trades()).isNotEmpty();
        assertThat(report.noSuggestion() + report.resolvedTrades() + report.notTakeable()
                + report.incomplete())
                .isEqualTo(report.entriesConsidered());
    }

    @Test
    @DisplayName("gapping below the stop before entry is NOT_TAKEABLE, not a fabricated loss")
    void aSetupThatGappedAwayIsNotTakeable() {
        List<Candle> bars = new ArrayList<>(oscillating(12));
        int entry = bars.size() - 1;
        // Next bar opens far below any suggested stop — you were never in the trade.
        bars.add(bar(bars.get(entry).date().plusDays(1), 10, 11, 9, 10));

        BacktestTrade trade = backtest.replayOne("TEST", bars, entry, BacktestSettings.defaults());

        assertThat(trade.outcome()).isEqualTo(BacktestOutcome.NOT_TAKEABLE);
        assertThat(trade.rMultiple()).isNull();
    }

    // ------------------------------------------------------------------------------- reporting

    @Test
    @DisplayName("the report names the honest sample size, not the flattering one")
    void reportsNonOverlappingEstimate() {
        BacktestReport report = backtest.replay("TEST", oscillating(12));

        assertThat(report.entriesConsidered()).isGreaterThan(0);
        assertThat(report.nonOverlappingEstimate())
                .isLessThanOrEqualTo(report.resolvedTrades())
                .isEqualTo(report.resolvedTrades() / BacktestSettings.DEFAULT_TIME_STOP_BARS);
    }

    @Test
    @DisplayName("censored tail entries are counted, never scored")
    void censoredEntriesAreExcludedFromMetrics() {
        BacktestReport report = backtest.replay("TEST", oscillating(12));

        assertThat(report.incomplete())
                .as("every series has a censored tail")
                .isGreaterThan(0);
        assertThat(report.targetFirst() + report.stopFirst() + report.timeouts())
                .as("incomplete trades contribute to no metric")
                .isEqualTo(report.resolvedTrades());
    }

    @Test
    void reportAggregatesOutcomesConsistently() {
        BacktestReport report = backtest.replay("TEST", oscillating(12));

        assertThat(report.targetFirst() + report.stopFirst() + report.timeouts())
                .isEqualTo(report.resolvedTrades());
        assertThat(report.hitRate()).isBetween(BigDecimal.ZERO, new BigDecimal("100"));
        if (report.resolvedTrades() > 0) {
            assertThat(report.worstR()).isLessThanOrEqualTo(report.expectancyR());
        }
    }

    @Test
    void handlesAnEmptySeries() {
        BacktestReport report = backtest.replay("TEST", List.of());

        assertThat(report.entriesConsidered()).isZero();
        assertThat(report.expectancyR()).isEqualByComparingTo("0");
        assertThat(report.nonOverlappingEstimate()).isZero();
    }

    @Test
    @DisplayName("the entry rule changes the fill price — R can still coincide, because R normalises")
    void entryRuleIsHonestAboutWhichPriceWasAvailable() {
        List<Candle> bars = new ArrayList<>(oscillating(12));
        int entry = bars.size() - 1;
        LocalDate d = bars.get(entry).date();
        bars.add(bar(d.plusDays(1), 47, 51, 46, 50));      // opens well below the signal close
        bars.add(bar(d.plusDays(2), 50, 75, 49, 74));

        BacktestTrade nextOpen = backtest.replayOne("TEST", bars, entry,
                new BacktestSettings(BacktestSettings.EntryRule.NEXT_OPEN, 15));
        BacktestTrade signalClose = backtest.replayOne("TEST", bars, entry,
                new BacktestSettings(BacktestSettings.EntryRule.SIGNAL_CLOSE, 15));

        // The fills genuinely differ: you cannot buy the close you are reacting to.
        assertThat(nextOpen.entryPrice()).isEqualByComparingTo("47");
        assertThat(signalClose.entryPrice()).isEqualByComparingTo(bars.get(entry).close());
        assertThat(nextOpen.entryPrice()).isNotEqualByComparingTo(signalClose.entryPrice());

        // Both risk the same distance to the same stop, so a clean stop is -1R either way. That is
        // R doing its job — it is the dollar loss that differs, and position sizing absorbs that.
        assertThat(nextOpen.stop()).isEqualByComparingTo(signalClose.stop());
    }
}
