package com.swingscope.service.journal;

import com.swingscope.domain.journal.JournalStats;
import com.swingscope.domain.journal.SetupType;
import com.swingscope.domain.journal.TradeJournalEntry;
import com.swingscope.domain.journal.TradeStatus;
import com.swingscope.repository.TradeJournalRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TradeJournalServiceTest {

    @Autowired
    private TradeJournalService journal;

    @Autowired
    private TradeJournalRepository repository;

    private TradeJournalEntry plan(String ticker, String entry, String stop, String target, int shares) {
        return journal.create(new TradeJournalEntry(ticker, SetupType.BREAKOUT,
                new BigDecimal(entry), new BigDecimal(stop), new BigDecimal(target),
                new BigDecimal("3.60"), shares, new BigDecimal("5.00")));
    }

    /** Plans, fills and closes a trade in one go, for building up a scorecard. */
    private TradeJournalEntry completed(String ticker, String fill, String exit, int shares,
                                        boolean rulesFollowed) {
        TradeJournalEntry e = plan(ticker, fill, "39.00", "43.60", shares);
        journal.markFilled(e.getId(), new BigDecimal(fill), shares);
        return journal.close(e.getId(), new BigDecimal(exit), "lesson", rulesFollowed);
    }

    // ------------------------------------------------------------------------------------ CRUD

    @Test
    void createsAPlannedEntryDatedToday() {
        TradeJournalEntry entry = plan("VZ", "40.00", "39.00", "43.60", 5);

        assertThat(entry.getId()).isNotNull();
        assertThat(entry.getStatus()).isEqualTo(TradeStatus.PLANNED);
        assertThat(entry.getDatePlanned()).isEqualTo(LocalDate.now());
        assertThat(entry.getDateFilled()).isNull();
        assertThat(entry.getRealizedPnl()).isNull();
    }

    @Test
    void findByIdRejectsAnUnknownId() {
        assertThatThrownBy(() -> journal.findById(9999L))
                .isInstanceOf(JournalEntryNotFoundException.class)
                .hasMessageContaining("9999");
    }

    @Test
    void listsNewestFirst() {
        plan("AAA", "10.00", "9.00", "13.00", 1);
        TradeJournalEntry second = plan("BBB", "20.00", "19.00", "23.00", 1);

        assertThat(journal.findAll()).first()
                .satisfies(e -> assertThat(e.getId()).isEqualTo(second.getId()));
    }

    @Test
    void deleteRemovesTheEntry() {
        TradeJournalEntry entry = plan("VZ", "40.00", "39.00", "43.60", 5);
        journal.delete(entry.getId());

        assertThat(repository.findById(entry.getId())).isEmpty();
    }

    // ---------------------------------------------------------------------------- transitions

    @Test
    void plannedToFilledRecordsTheActualFillAndDate() {
        TradeJournalEntry entry = plan("VZ", "40.00", "39.00", "43.60", 5);

        TradeJournalEntry filled = journal.markFilled(entry.getId(), new BigDecimal("40.05"), 4);

        assertThat(filled.getStatus()).isEqualTo(TradeStatus.FILLED);
        assertThat(filled.getFillPrice()).isEqualByComparingTo("40.05");
        assertThat(filled.getShares()).isEqualTo(4);     // actual fill overrode the plan
        assertThat(filled.getDateFilled()).isEqualTo(LocalDate.now());
    }

    @Test
    void fillingWithoutAPriceIsRejected() {
        TradeJournalEntry entry = plan("VZ", "40.00", "39.00", "43.60", 5);

        assertThatThrownBy(() -> journal.markFilled(entry.getId(), null, null))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("fill price above 0");
    }

    @Test
    void aNoFillIsExcludedFromTheScorecard() {
        TradeJournalEntry entry = plan("VZ", "40.00", "39.00", "43.60", 5);
        journal.markNoFill(entry.getId());

        JournalStats stats = journal.stats();
        assertThat(stats.noFills()).isEqualTo(1);
        assertThat(stats.closedCount()).isZero();
        assertThat(stats.winRate()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a trade that never filled cannot be closed")
    void closingAnUnfilledTradeIsRejected() {
        TradeJournalEntry entry = plan("VZ", "40.00", "39.00", "43.60", 5);

        assertThatThrownBy(() -> journal.close(entry.getId(), new BigDecimal("43.00"), "x", true))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("cannot move a trade from PLANNED");
    }

    @Test
    void anAlreadyFilledTradeCannotBeFilledAgain() {
        TradeJournalEntry entry = plan("VZ", "40.00", "39.00", "43.60", 5);
        journal.markFilled(entry.getId(), new BigDecimal("40.00"), 5);

        assertThatThrownBy(() -> journal.markFilled(entry.getId(), new BigDecimal("41.00"), 5))
                .isInstanceOf(InvalidTransitionException.class);
    }

    // ----------------------------------------------------------------------------- closing out

    @Test
    @DisplayName("the outcome is derived from the exit price — a loser cannot be filed as a win")
    void closingDerivesTheOutcomeFromPnl() {
        TradeJournalEntry win = completed("VZ", "40.00", "43.60", 5, true);
        assertThat(win.getStatus()).isEqualTo(TradeStatus.CLOSED_WIN);
        assertThat(win.getRealizedPnl()).isEqualByComparingTo("18.00");   // (43.60−40.00) × 5

        TradeJournalEntry loss = completed("CI", "40.00", "39.00", 5, true);
        assertThat(loss.getStatus()).isEqualTo(TradeStatus.CLOSED_LOSS);
        assertThat(loss.getRealizedPnl()).isEqualByComparingTo("-5.00");

        TradeJournalEntry flat = completed("XYZ", "40.00", "40.00", 5, true);
        assertThat(flat.getStatus()).isEqualTo(TradeStatus.SCRATCH);
        assertThat(flat.getRealizedPnl()).isEqualByComparingTo("0.00");
    }

    @Test
    void closingRequiresTheLessonAndTheRulesAnswer() {
        TradeJournalEntry entry = plan("VZ", "40.00", "39.00", "43.60", 5);
        journal.markFilled(entry.getId(), new BigDecimal("40.00"), 5);
        Long id = entry.getId();

        assertThatThrownBy(() -> journal.close(id, new BigDecimal("43.00"), "  ", true))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("lesson is required");

        assertThatThrownBy(() -> journal.close(id, new BigDecimal("43.00"), "learned something", null))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("rules were followed");

        assertThatThrownBy(() -> journal.close(id, null, "learned something", true))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("exit price above 0");
    }

    @Test
    void closingStampsTheDateAndKeepsTheLesson() {
        TradeJournalEntry closed = completed("VZ", "40.00", "43.60", 5, true);

        assertThat(closed.getDateClosed()).isEqualTo(LocalDate.now());
        assertThat(closed.getLessonText()).isEqualTo("lesson");
        assertThat(closed.getRulesFollowed()).isTrue();
    }

    // ------------------------------------------------------------------------------- scorecard

    @Test
    void anEmptyJournalHasAZeroedScorecard() {
        JournalStats stats = journal.stats();

        assertThat(stats.totalEntries()).isZero();
        assertThat(stats.closedCount()).isZero();
        assertThat(stats.netPnl()).isEqualByComparingTo("0");
        assertThat(stats.graduationMet()).isFalse();
        assertThat(stats.graduationTarget()).isEqualTo(25);
    }

    @Test
    @DisplayName("win rate and expectancy count wins and losses only")
    void scorecardCountsCompletedTradesOnly() {
        completed("AAA", "40.00", "44.00", 5, true);     // +20.00 win
        completed("BBB", "40.00", "38.00", 5, true);     // −10.00 loss
        completed("CCC", "40.00", "40.00", 5, true);     // scratch — excluded
        journal.markNoFill(plan("DDD", "40.00", "39.00", "43.60", 5).getId());   // excluded

        JournalStats stats = journal.stats();

        assertThat(stats.totalEntries()).isEqualTo(4);
        assertThat(stats.closedCount()).isEqualTo(2);
        assertThat(stats.wins()).isEqualTo(1);
        assertThat(stats.losses()).isEqualTo(1);
        assertThat(stats.scratches()).isEqualTo(1);
        assertThat(stats.noFills()).isEqualTo(1);
        assertThat(stats.winRate()).isEqualByComparingTo("50.0");
        assertThat(stats.netPnl()).isEqualByComparingTo("10.00");
        assertThat(stats.expectancy()).isEqualByComparingTo("5.00");   // 10.00 ÷ 2
        assertThat(stats.averageWin()).isEqualByComparingTo("20.00");
        assertThat(stats.averageLoss()).isEqualByComparingTo("-10.00");
    }

    @Test
    void openTradesAreCountedSeparately() {
        plan("AAA", "40.00", "39.00", "43.60", 5);                              // planned
        TradeJournalEntry filled = plan("BBB", "40.00", "39.00", "43.60", 5);
        journal.markFilled(filled.getId(), new BigDecimal("40.00"), 5);          // filled
        completed("CCC", "40.00", "44.00", 5, true);                            // done

        JournalStats stats = journal.stats();

        assertThat(stats.openTrades()).isEqualTo(2);
        assertThat(stats.closedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("graduation needs the count, a positive total, and rules followed on every loser")
    void graduationRequiresAllThreeGates() {
        // 25 wins: count and net are fine, no losers to fail the rules gate.
        for (int i = 0; i < 25; i++) {
            completed("T" + i, "40.00", "41.00", 1, true);
        }

        JournalStats stats = journal.stats();
        assertThat(stats.closedCount()).isEqualTo(25);
        assertThat(stats.graduationPercent()).isEqualByComparingTo("100");
        assertThat(stats.graduationMet()).isTrue();

        // One loss taken outside the rules blocks it, even with the count and net still fine.
        completed("BAD", "40.00", "39.00", 1, false);

        JournalStats after = journal.stats();
        assertThat(after.netPnl().signum()).isPositive();
        assertThat(after.losses()).isEqualTo(1);
        assertThat(after.losersWithRulesFollowed()).isZero();
        assertThat(after.graduationMet()).isFalse();
    }

    @Test
    void graduationIsNotMetWhileTheNetTotalIsNegative() {
        for (int i = 0; i < 25; i++) {
            completed("T" + i, "40.00", "39.00", 1, true);   // 25 disciplined losses
        }

        JournalStats stats = journal.stats();

        assertThat(stats.closedCount()).isEqualTo(25);
        assertThat(stats.losersWithRulesFollowed()).isEqualTo(25);
        assertThat(stats.netPnl().signum()).isNegative();
        assertThat(stats.graduationMet()).isFalse();
    }

    @Test
    void graduationProgressIsCappedAtOneHundredPercent() {
        for (int i = 0; i < 30; i++) {
            completed("T" + i, "40.00", "41.00", 1, true);
        }

        assertThat(journal.stats().graduationPercent()).isEqualByComparingTo("100");
    }

    // ---------------------------------------------------------------------------------- update

    @Test
    void updateRewritesThePlanAndRecomputesPnlOnClosedTrades() {
        TradeJournalEntry closed = completed("VZ", "40.00", "44.00", 5, true);
        assertThat(closed.getRealizedPnl()).isEqualByComparingTo("20.00");

        TradeJournalEntry changes = new TradeJournalEntry("VZ", SetupType.PULLBACK,
                new BigDecimal("40.00"), new BigDecimal("39.00"), new BigDecimal("43.60"),
                new BigDecimal("3.60"), 10, new BigDecimal("5.00"));
        TradeJournalEntry updated = journal.update(closed.getId(), changes);

        assertThat(updated.getSetupType()).isEqualTo(SetupType.PULLBACK);
        assertThat(updated.getShares()).isEqualTo(10);
        assertThat(updated.getRealizedPnl()).isEqualByComparingTo("40.00");   // recomputed for 10
    }

    @Test
    void riskAtEntryUsesTheFillOnceKnown() {
        TradeJournalEntry entry = plan("VZ", "40.00", "39.00", "43.60", 5);
        assertThat(entry.riskAtEntry()).isEqualByComparingTo("5.00");   // planned entry

        journal.markFilled(entry.getId(), new BigDecimal("40.20"), 5);
        assertThat(entry.riskAtEntry()).isEqualByComparingTo("6.00");   // actual fill
    }

    // -------------------------------------------------------------------- rejected setups

    @Test
    @DisplayName("a rejected setup records the refusal reason and never opened")
    void rejectedSetupsKeepTheReasonAndNoPnl() {
        TradeJournalEntry rejected = journal.create(TradeJournalEntry.rejected(
                "CI", SetupType.BREAKOUT, new BigDecimal("20.00"), new BigDecimal("19.00"),
                new BigDecimal("21.87"), new BigDecimal("1.87"), 5, new BigDecimal("5.00"),
                "ratio 1.87 < 2.0"));

        assertThat(rejected.getStatus()).isEqualTo(TradeStatus.REJECTED);
        assertThat(rejected.getStatus().isTerminal()).isTrue();
        assertThat(rejected.getStatus().isCountedTrade()).isFalse();
        assertThat(rejected.getLessonText()).isEqualTo("Rejected by the rules: ratio 1.87 < 2.0");
        assertThat(rejected.getFillPrice()).isNull();
        assertThat(rejected.getRealizedPnl()).isNull();
    }

    @Test
    @DisplayName("rejected setups stay out of the scorecard and the open-trade count")
    void rejectedSetupsDoNotTouchTheScorecard() {
        completed("WIN", "40.00", "44.00", 5, true);          // +20.00
        journal.create(TradeJournalEntry.rejected("CI", SetupType.BREAKOUT,
                new BigDecimal("20.00"), new BigDecimal("19.00"), new BigDecimal("21.87"),
                new BigDecimal("1.87"), 5, new BigDecimal("5.00"), "ratio 1.87 < 2.0"));

        JournalStats stats = journal.stats();

        assertThat(stats.totalEntries()).isEqualTo(2);
        assertThat(stats.rejected()).isEqualTo(1);
        assertThat(stats.closedCount()).isEqualTo(1);      // the rejection is not a trade
        assertThat(stats.openTrades()).isZero();           // nor is it open
        assertThat(stats.winRate()).isEqualByComparingTo("100.0");
        assertThat(stats.netPnl()).isEqualByComparingTo("20.00");
    }

    @Test
    void aRejectedSetupCannotBeFilledOrClosed() {
        TradeJournalEntry rejected = journal.create(TradeJournalEntry.rejected(
                "CI", SetupType.OTHER, new BigDecimal("20.00"), new BigDecimal("19.00"),
                new BigDecimal("21.87"), new BigDecimal("1.87"), 5, new BigDecimal("5.00"), "no"));
        Long id = rejected.getId();

        assertThatThrownBy(() -> journal.markFilled(id, new BigDecimal("20.00"), 5))
                .isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> journal.close(id, new BigDecimal("21.00"), "x", true))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void aRejectionWithoutAReasonStillReadsSensibly() {
        TradeJournalEntry rejected = TradeJournalEntry.rejected("X", null,
                new BigDecimal("10"), new BigDecimal("9"), new BigDecimal("11"),
                null, 0, null, "  ");

        assertThat(rejected.getLessonText()).isEqualTo("Rejected by the rules.");
        assertThat(rejected.getSetupType()).isEqualTo(SetupType.OTHER);
    }

    @Test
    void pnlIsNullUntilBothPricesAreKnown() {
        assertThat(TradeJournalEntry.computePnl(null, new BigDecimal("40.00"), 5)).isNull();
        assertThat(TradeJournalEntry.computePnl(new BigDecimal("40.00"), null, 5)).isNull();
    }
}
