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

import java.math.BigDecimal;
import java.time.LocalDate;

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
