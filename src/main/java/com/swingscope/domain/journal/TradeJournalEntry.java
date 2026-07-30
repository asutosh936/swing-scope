package com.swingscope.domain.journal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * One trade's whole life: the plan, what actually filled, how it ended, and what was learned.
 *
 * <p>The journal is the scorecard and the real-money graduation gate, so the fields that make a
 * trade <em>countable</em> — outcome, lesson, rules-followed — are required at close time rather
 * than optional.
 */
@Entity
@Table(name = "trade_journal_entry")
public class TradeJournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 12)
    private String ticker;

    // columnDefinition pins this to VARCHAR. Left to itself, Hibernate maps an enum to H2's native
    // ENUM type, which fixes the permitted values at creation time — and `ddl-auto: update` will not
    // widen it later. Adding a new enum constant then fails at INSERT on any pre-existing database.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private SetupType setupType = SetupType.OTHER;

    // ---- the plan (from the calculator) ----
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal entry;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal stop;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal target;

    @Column(precision = 8, scale = 2)
    private BigDecimal ratio;

    @Column(nullable = false)
    private int shares;

    @Column(precision = 12, scale = 2)
    private BigDecimal riskAmount;

    // ---- execution ----
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private TradeStatus status = TradeStatus.PLANNED;

    @Column(precision = 12, scale = 2)
    private BigDecimal fillPrice;

    @Column(precision = 12, scale = 2)
    private BigDecimal exitPrice;

    @Column(precision = 12, scale = 2)
    private BigDecimal realizedPnl;

    // ---- dates ----
    @Column(nullable = false)
    private LocalDate datePlanned = LocalDate.now();

    private LocalDate dateFilled;

    private LocalDate dateClosed;

    // ---- the point of the whole exercise ----
    @Column(length = 500)
    private String lessonText;

    private Boolean rulesFollowed;

    protected TradeJournalEntry() {
        // for JPA
    }

    public TradeJournalEntry(String ticker, SetupType setupType, BigDecimal entry, BigDecimal stop,
                             BigDecimal target, BigDecimal ratio, int shares, BigDecimal riskAmount) {
        this.ticker = ticker;
        this.setupType = setupType == null ? SetupType.OTHER : setupType;
        this.entry = entry;
        this.stop = stop;
        this.target = target;
        this.ratio = ratio;
        this.shares = shares;
        this.riskAmount = riskAmount;
        this.status = TradeStatus.PLANNED;
        this.datePlanned = LocalDate.now();
    }

    /**
     * A setup the rules refused, kept as a record. It never opened, so there is no fill, no exit and
     * no P&L — the refusal reason is stored as the lesson, which is exactly what it is.
     */
    public static TradeJournalEntry rejected(String ticker, SetupType setupType, BigDecimal entry,
                                             BigDecimal stop, BigDecimal target, BigDecimal ratio,
                                             int shares, BigDecimal riskAmount, String reason) {
        TradeJournalEntry rejected = new TradeJournalEntry(
                ticker, setupType, entry, stop, target, ratio, shares, riskAmount);
        rejected.status = TradeStatus.REJECTED;
        rejected.lessonText = reason == null || reason.isBlank()
                ? "Rejected by the rules." : "Rejected by the rules: " + reason;
        return rejected;
    }

    /**
     * Long-only realized P&L: {@code (exit − fill) × shares}. No commissions — this is a paper
     * scorecard, not an accounting ledger.
     */
    public static BigDecimal computePnl(BigDecimal fillPrice, BigDecimal exitPrice, int shares) {
        if (fillPrice == null || exitPrice == null) {
            return null;
        }
        return exitPrice.subtract(fillPrice)
                .multiply(BigDecimal.valueOf(shares))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Money actually at risk once filled, or the planned risk when it hasn't filled yet. */
    public BigDecimal riskAtEntry() {
        BigDecimal basis = fillPrice != null ? fillPrice : entry;
        if (basis == null || stop == null) {
            return null;
        }
        return basis.subtract(stop).multiply(BigDecimal.valueOf(shares)).setScale(2, RoundingMode.HALF_UP);
    }

    public Long getId() {
        return id;
    }

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public SetupType getSetupType() {
        return setupType;
    }

    public void setSetupType(SetupType setupType) {
        this.setupType = setupType;
    }

    public BigDecimal getEntry() {
        return entry;
    }

    public void setEntry(BigDecimal entry) {
        this.entry = entry;
    }

    public BigDecimal getStop() {
        return stop;
    }

    public void setStop(BigDecimal stop) {
        this.stop = stop;
    }

    public BigDecimal getTarget() {
        return target;
    }

    public void setTarget(BigDecimal target) {
        this.target = target;
    }

    public BigDecimal getRatio() {
        return ratio;
    }

    public void setRatio(BigDecimal ratio) {
        this.ratio = ratio;
    }

    public int getShares() {
        return shares;
    }

    public void setShares(int shares) {
        this.shares = shares;
    }

    public BigDecimal getRiskAmount() {
        return riskAmount;
    }

    public void setRiskAmount(BigDecimal riskAmount) {
        this.riskAmount = riskAmount;
    }

    public TradeStatus getStatus() {
        return status;
    }

    public void setStatus(TradeStatus status) {
        this.status = status;
    }

    public BigDecimal getFillPrice() {
        return fillPrice;
    }

    public void setFillPrice(BigDecimal fillPrice) {
        this.fillPrice = fillPrice;
    }

    public BigDecimal getExitPrice() {
        return exitPrice;
    }

    public void setExitPrice(BigDecimal exitPrice) {
        this.exitPrice = exitPrice;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(BigDecimal realizedPnl) {
        this.realizedPnl = realizedPnl;
    }

    public LocalDate getDatePlanned() {
        return datePlanned;
    }

    public void setDatePlanned(LocalDate datePlanned) {
        this.datePlanned = datePlanned;
    }

    public LocalDate getDateFilled() {
        return dateFilled;
    }

    public void setDateFilled(LocalDate dateFilled) {
        this.dateFilled = dateFilled;
    }

    public LocalDate getDateClosed() {
        return dateClosed;
    }

    public void setDateClosed(LocalDate dateClosed) {
        this.dateClosed = dateClosed;
    }

    public String getLessonText() {
        return lessonText;
    }

    public void setLessonText(String lessonText) {
        this.lessonText = lessonText;
    }

    public Boolean getRulesFollowed() {
        return rulesFollowed;
    }

    public void setRulesFollowed(Boolean rulesFollowed) {
        this.rulesFollowed = rulesFollowed;
    }
}
