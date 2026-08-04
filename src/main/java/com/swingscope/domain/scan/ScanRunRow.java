package com.swingscope.domain.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.swingscope.domain.candidate.AnalysisConfidence;
import com.swingscope.domain.candidate.CandidateRow;
import com.swingscope.domain.candidate.CandidateVerdict;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One ticker's row within a stored scan — the facts as they stood at scan time.
 *
 * <p>Deliberately a snapshot, not a live view. Re-deriving a past tier from today's prices would
 * rewrite history and destroy the only thing this table is good for: checking, later, what the tool
 * actually said at the time.
 */
@Entity
@Table(name = "scan_run_row")
public class ScanRunRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private ScanRun run;

    @Column(nullable = false, length = 12)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Tier tier;

    @Column(length = 300)
    private String reason;

    @Column(precision = 12, scale = 4)
    private BigDecimal price;

    @Column(precision = 10, scale = 2)
    private BigDecimal changePercent;

    @Column(precision = 12, scale = 4)
    private BigDecimal ema20;

    @Column(precision = 12, scale = 4)
    private BigDecimal ema50;

    @Column(precision = 12, scale = 4)
    private BigDecimal ema200;

    @Column(precision = 10, scale = 2)
    private BigDecimal distanceToEma50Percent;

    private Long volume;

    private Long averageVolume;

    @Column(precision = 18, scale = 4)
    private BigDecimal marketCapMillions;

    private LocalDate nextEarningsDate;

    private Boolean inUptrend;

    private boolean bigMover;

    private boolean earningsWithin3Days;

    // ---- Phase 8: the auto-analysis, stored so a reloaded scan shows what it showed at the time.
    // All nullable: rows outside Tier 1/2 are never analysed, and scans stored before Phase 8 have
    // none of this. A null verdict is the signal to fall back to the plain tier table.

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CandidateVerdict verdict;

    private BigDecimal suggestedStop;

    private BigDecimal suggestedTarget;

    private BigDecimal ratio;

    private Integer shares;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private AnalysisConfidence.Grade confidenceGrade;

    private Integer confidenceMet;

    private Integer confidenceTotal;

    @Column(length = 1000)
    private String confidenceDetail;

    /** Display strings, joined — see CandidateRow for why these are not rebuilt from objects. */
    @Column(length = 1000)
    private String weaknesses;

    @Column(length = 1000)
    private String needed;

    @Column(length = 500)
    private String failReason;

    private static final String SEP = " | ";

    protected ScanRunRow() {
        // for JPA
    }

    public static ScanRunRow from(TieredStock stock) {
        ScanRunRow row = new ScanRunRow();
        row.symbol = stock.symbol();
        row.tier = stock.tier();
        row.reason = stock.reason();
        row.price = stock.price();
        row.changePercent = stock.changePercent();
        row.ema20 = stock.ema20();
        row.ema50 = stock.ema50();
        row.ema200 = stock.ema200();
        row.distanceToEma50Percent = stock.distanceToEma50Percent();
        row.volume = stock.volume();
        row.averageVolume = stock.averageVolume();
        row.marketCapMillions = stock.marketCapMillions();
        row.nextEarningsDate = stock.nextEarningsDate();
        row.inUptrend = stock.inUptrend();
        row.bigMover = stock.bigMover();
        row.earningsWithin3Days = stock.earningsWithin3Days();
        return row;
    }

    /** Attaches the auto-analysis to an already-built row. */
    public void setAnalysis(CandidateRow c) {
        this.verdict = c.verdict();
        this.suggestedStop = c.stop();
        this.suggestedTarget = c.target();
        this.ratio = c.ratio();
        this.shares = c.shares();
        this.confidenceGrade = c.grade();
        this.confidenceMet = c.confidenceMet();
        this.confidenceTotal = c.confidenceTotal();
        this.confidenceDetail = truncate(c.confidenceDetail(), 1000);
        this.weaknesses = truncate(String.join(SEP, c.weaknesses()), 1000);
        this.needed = truncate(String.join(SEP, c.needed()), 1000);
        this.failReason = truncate(c.failReason(), 500);
    }

    /** Null for a row that was never analysed — the view then falls back to the plain table. */
    public CandidateRow toCandidateRow() {
        if (verdict == null) {
            return null;
        }
        return new CandidateRow(symbol, tier, price, suggestedStop, suggestedTarget, ratio, shares,
                verdict, confidenceGrade,
                confidenceMet == null ? 0 : confidenceMet,
                confidenceTotal == null ? 0 : confidenceTotal,
                confidenceDetail, split(weaknesses), split(needed), failReason);
    }

    private static List<String> split(String joined) {
        return joined == null || joined.isBlank() ? List.of() : List.of(joined.split(" \\| "));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public TieredStock toTieredStock() {
        return new TieredStock(symbol, tier, reason, price, changePercent, ema20, ema50, ema200,
                distanceToEma50Percent, volume, averageVolume, marketCapMillions, nextEarningsDate,
                inUptrend, bigMover, earningsWithin3Days);
    }

    void setRun(ScanRun run) {
        this.run = run;
    }

    public String getSymbol() {
        return symbol;
    }

    public Tier getTier() {
        return tier;
    }
}
