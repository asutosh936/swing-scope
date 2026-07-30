package com.swingscope.service.journal;

import com.swingscope.domain.TradeAnalysis;
import com.swingscope.domain.journal.JournalStats;
import com.swingscope.domain.journal.SetupType;
import com.swingscope.domain.journal.TradeJournalEntry;
import com.swingscope.domain.journal.TradeStatus;
import com.swingscope.repository.TradeJournalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * The journal's rules: which status moves are legal, what a close requires, and how the scorecard
 * adds up.
 *
 * <p>Nothing here evaluates whether a trade was a <em>good idea</em> — it records what happened and
 * does the arithmetic. The graduation gate is a fact about the record, not permission to trade.
 */
@Service
public class TradeJournalService {

    private static final Logger log = LoggerFactory.getLogger(TradeJournalService.class);

    /** Completed trades needed before considering real money — the plan's 25–30 range, lower bound. */
    static final int GRADUATION_TARGET = 25;

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final TradeJournalRepository repository;

    public TradeJournalService(TradeJournalRepository repository) {
        this.repository = repository;
    }

    // ------------------------------------------------------------------------------------- CRUD

    @Transactional(readOnly = true)
    public List<TradeJournalEntry> findAll() {
        return repository.findAllByOrderByDatePlannedDescIdDesc();
    }

    @Transactional(readOnly = true)
    public TradeJournalEntry findById(Long id) {
        return repository.findById(id).orElseThrow(() -> new JournalEntryNotFoundException(id));
    }

    @Transactional
    public TradeJournalEntry create(TradeJournalEntry entry) {
        TradeJournalEntry saved = repository.save(entry);
        log.info("Journal entry #{} created: {} {} entry={} stop={} target={} shares={} status={}",
                saved.getId(), saved.getTicker(), saved.getSetupType(), saved.getEntry(),
                saved.getStop(), saved.getTarget(), saved.getShares(), saved.getStatus());
        return saved;
    }

    /**
     * Task 5.8 — turns a calculator result straight into a PLANNED entry, so planning and
     * journalling are one step rather than two. Phase 4's "Plan this trade" will reuse this.
     */
    @Transactional
    public TradeJournalEntry planFromAnalysis(TradeAnalysis analysis, BigDecimal stop,
                                              BigDecimal target, BigDecimal entryPrice,
                                              SetupType setupType) {
        TradeJournalEntry entry = new TradeJournalEntry(
                analysis.ticker(), setupType, entryPrice, stop, target,
                analysis.ratio(), analysis.wholeShares(), analysis.totalRisk());
        log.info("Journalling planned trade for {} straight from the calculator (ratio {}, {} shares)",
                analysis.ticker(), analysis.ratio(), analysis.wholeShares());
        return create(entry);
    }

    @Transactional
    public TradeJournalEntry update(Long id, TradeJournalEntry changes) {
        TradeJournalEntry existing = findById(id);
        existing.setTicker(changes.getTicker());
        existing.setSetupType(changes.getSetupType());
        existing.setEntry(changes.getEntry());
        existing.setStop(changes.getStop());
        existing.setTarget(changes.getTarget());
        existing.setRatio(changes.getRatio());
        existing.setShares(changes.getShares());
        existing.setRiskAmount(changes.getRiskAmount());
        existing.setLessonText(changes.getLessonText());
        if (changes.getRulesFollowed() != null) {
            existing.setRulesFollowed(changes.getRulesFollowed());
        }
        // Recompute P&L in case shares or the fill changed on an already-closed trade.
        if (existing.getStatus().isClosed()) {
            existing.setRealizedPnl(TradeJournalEntry.computePnl(
                    existing.getFillPrice(), existing.getExitPrice(), existing.getShares()));
        }
        log.info("Journal entry #{} updated ({})", id, existing.getTicker());
        return existing;
    }

    @Transactional
    public void delete(Long id) {
        TradeJournalEntry entry = findById(id);
        repository.delete(entry);
        log.warn("Journal entry #{} deleted ({}) — the scorecard no longer counts it",
                id, entry.getTicker());
    }

    // ------------------------------------------------------------------------- status transitions

    /** PLANNED → FILLED. The fill price is what actually happened, not what was planned. */
    @Transactional
    public TradeJournalEntry markFilled(Long id, BigDecimal fillPrice, Integer actualShares) {
        TradeJournalEntry entry = findById(id);
        requireStatus(entry, TradeStatus.FILLED, TradeStatus.PLANNED);
        if (fillPrice == null || fillPrice.signum() <= 0) {
            throw new InvalidTransitionException("a fill price above 0 is required to mark a trade filled");
        }
        entry.setFillPrice(fillPrice);
        if (actualShares != null && actualShares > 0) {
            entry.setShares(actualShares);
        }
        entry.setStatus(TradeStatus.FILLED);
        entry.setDateFilled(LocalDate.now());
        log.info("Journal entry #{} FILLED at {} x{} shares", id, fillPrice, entry.getShares());
        return entry;
    }

    /** PLANNED → NO_FILL. The order never triggered; nothing to score. */
    @Transactional
    public TradeJournalEntry markNoFill(Long id) {
        TradeJournalEntry entry = findById(id);
        requireStatus(entry, TradeStatus.NO_FILL, TradeStatus.PLANNED);
        entry.setStatus(TradeStatus.NO_FILL);
        log.info("Journal entry #{} marked NO_FILL — excluded from the scorecard", id);
        return entry;
    }

    /**
     * FILLED → CLOSED_*. The outcome is <em>derived</em> from the exit price rather than chosen, so
     * a losing trade cannot be filed as a win. Exactly break-even is a SCRATCH.
     *
     * <p>The lesson and the rules-followed answer are required here — that is the entire point of
     * keeping a journal, and it is the one moment the information is fresh.
     */
    @Transactional
    public TradeJournalEntry close(Long id, BigDecimal exitPrice, String lesson, Boolean rulesFollowed) {
        TradeJournalEntry entry = findById(id);
        if (entry.getStatus() != TradeStatus.FILLED) {
            throw new InvalidTransitionException(entry.getStatus(), TradeStatus.CLOSED_WIN);
        }
        if (exitPrice == null || exitPrice.signum() <= 0) {
            throw new InvalidTransitionException("an exit price above 0 is required to close a trade");
        }
        if (lesson == null || lesson.isBlank()) {
            throw new InvalidTransitionException("a one-sentence lesson is required to close a trade");
        }
        if (rulesFollowed == null) {
            throw new InvalidTransitionException(
                    "record whether the rules were followed — especially on a loser");
        }

        BigDecimal pnl = TradeJournalEntry.computePnl(entry.getFillPrice(), exitPrice, entry.getShares());
        TradeStatus outcome = switch (Integer.signum(pnl.signum())) {
            case 1 -> TradeStatus.CLOSED_WIN;
            case -1 -> TradeStatus.CLOSED_LOSS;
            default -> TradeStatus.SCRATCH;
        };

        entry.setExitPrice(exitPrice);
        entry.setRealizedPnl(pnl);
        entry.setStatus(outcome);
        entry.setDateClosed(LocalDate.now());
        entry.setLessonText(lesson.trim());
        entry.setRulesFollowed(rulesFollowed);

        log.info("Journal entry #{} closed as {}: exit={} pnl={} rulesFollowed={}",
                id, outcome, exitPrice, pnl, rulesFollowed);
        if (outcome == TradeStatus.CLOSED_LOSS && Boolean.FALSE.equals(rulesFollowed)) {
            log.warn("Journal entry #{} was a loss taken OUTSIDE the rules — this blocks graduation", id);
        }
        return entry;
    }

    private static void requireStatus(TradeJournalEntry entry, TradeStatus to, TradeStatus... allowedFrom) {
        for (TradeStatus allowed : allowedFrom) {
            if (entry.getStatus() == allowed) {
                return;
            }
        }
        throw new InvalidTransitionException(entry.getStatus(), to);
    }

    // ------------------------------------------------------------------------------- scorecard

    /**
     * Win rate, expectancy and graduation progress over completed trades only.
     *
     * <p>Expectancy here is the plain average dollars per completed trade — net P&amp;L divided by
     * the number of wins and losses.
     */
    @Transactional(readOnly = true)
    public JournalStats stats() {
        List<TradeJournalEntry> all = repository.findAll();
        if (all.isEmpty()) {
            return JournalStats.empty(GRADUATION_TARGET);
        }

        long openTrades = all.stream()
                .filter(e -> !e.getStatus().isTerminal())
                .count();
        long scratches = all.stream().filter(e -> e.getStatus() == TradeStatus.SCRATCH).count();
        long noFills = all.stream().filter(e -> e.getStatus() == TradeStatus.NO_FILL).count();
        long rejected = all.stream().filter(e -> e.getStatus() == TradeStatus.REJECTED).count();

        List<TradeJournalEntry> counted = all.stream()
                .filter(e -> e.getStatus().isCountedTrade())
                .toList();

        long wins = counted.stream().filter(e -> e.getStatus() == TradeStatus.CLOSED_WIN).count();
        long losses = counted.stream().filter(e -> e.getStatus() == TradeStatus.CLOSED_LOSS).count();
        long closedCount = counted.size();

        BigDecimal netPnl = counted.stream()
                .map(TradeJournalEntry::getRealizedPnl)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal winRate = closedCount == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(wins).multiply(ONE_HUNDRED)
                        .divide(BigDecimal.valueOf(closedCount), 1, RoundingMode.HALF_UP);

        BigDecimal expectancy = closedCount == 0 ? BigDecimal.ZERO
                : netPnl.divide(BigDecimal.valueOf(closedCount), 2, RoundingMode.HALF_UP);

        BigDecimal averageWin = average(counted, TradeStatus.CLOSED_WIN, wins);
        BigDecimal averageLoss = average(counted, TradeStatus.CLOSED_LOSS, losses);

        long losersWithRulesFollowed = counted.stream()
                .filter(e -> e.getStatus() == TradeStatus.CLOSED_LOSS)
                .filter(e -> Boolean.TRUE.equals(e.getRulesFollowed()))
                .count();

        BigDecimal graduationPercent = BigDecimal.valueOf(Math.min(closedCount, GRADUATION_TARGET))
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(GRADUATION_TARGET), 0, RoundingMode.DOWN);

        boolean graduationMet = closedCount >= GRADUATION_TARGET
                && netPnl.signum() > 0
                && losersWithRulesFollowed == losses;

        JournalStats stats = new JournalStats(all.size(), openTrades, closedCount, wins, losses,
                scratches, noFills, rejected, winRate, netPnl, expectancy, averageWin, averageLoss,
                losersWithRulesFollowed, GRADUATION_TARGET, graduationPercent, graduationMet);

        log.debug("Scorecard: {} closed ({}W/{}L), winRate={}%, net={}, expectancy={}, graduation {}%{}",
                closedCount, wins, losses, winRate, netPnl, expectancy, graduationPercent,
                graduationMet ? " — MET" : "");
        return stats;
    }

    private static BigDecimal average(List<TradeJournalEntry> counted, TradeStatus status, long n) {
        if (n == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = counted.stream()
                .filter(e -> e.getStatus() == status)
                .map(TradeJournalEntry::getRealizedPnl)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
    }
}
