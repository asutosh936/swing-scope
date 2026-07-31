package com.swingscope.domain.scan;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A completed scan, stored so it outlives a restart.
 *
 * <p>Beyond convenience, the history is evidence: with enough stored scans you can ask whether a
 * Tier 1 classification actually preceded anything, which is the same question Phase 6A asks of the
 * suggested levels. A scan that exists only in memory can never be checked.
 *
 * <p>Only finished scans are written. A run still in flight lives in memory, where its progress is
 * mutable; persisting every tick would be write amplification for no gain.
 */
@Entity
@Table(name = "scan_run")
public class ScanRun {

    /** The short job id the URL already uses, so a stored scan keeps its address. */
    @Id
    @Column(length = 16)
    private String id;

    @Column(nullable = false, length = 1000)
    private String tickers;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScanJob.Status status = ScanJob.Status.COMPLETE;

    @Column(nullable = false)
    private Instant startedAt = Instant.now();

    private Instant finishedAt;

    private long elapsedMillis;

    private int requested;

    @Column(length = 500)
    private String error;

    @Column(length = 2000)
    private String warnings;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("id ASC")
    private List<ScanRunRow> rows = new ArrayList<>();

    protected ScanRun() {
        // for JPA
    }

    public ScanRun(String id, List<String> tickers, Instant startedAt) {
        this.id = id;
        this.tickers = String.join(",", tickers);
        this.requested = tickers.size();
        this.startedAt = startedAt;
    }

    public void complete(ScanResult result, Instant finishedAt) {
        this.status = ScanJob.Status.COMPLETE;
        this.finishedAt = finishedAt;
        this.elapsedMillis = result.elapsedMillis();
        this.requested = result.requested();
        this.warnings = String.join(" | ", result.warnings());
        this.rows.clear();
        for (TieredStock stock : result.stocks()) {
            ScanRunRow row = ScanRunRow.from(stock);
            row.setRun(this);
            this.rows.add(row);
        }
    }

    public void fail(String message, Instant finishedAt) {
        this.status = ScanJob.Status.FAILED;
        this.error = message == null ? null : message.substring(0, Math.min(500, message.length()));
        this.finishedAt = finishedAt;
    }

    /** Rebuilds the view model, through the same grouping a live scan uses. */
    public ScanResult toResult() {
        List<TieredStock> stocks = rows.stream().map(ScanRunRow::toTieredStock).toList();
        List<String> parsedWarnings = warnings == null || warnings.isBlank()
                ? List.of() : List.of(warnings.split(" \\| "));
        return ScanResult.of(stocks, requested, elapsedMillis, parsedWarnings);
    }

    public List<String> tickerList() {
        return tickers == null || tickers.isBlank() ? List.of() : List.of(tickers.split(","));
    }

    public String getId() {
        return id;
    }

    public String getTickers() {
        return tickers;
    }

    public ScanJob.Status getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public int getRequested() {
        return requested;
    }

    public String getError() {
        return error;
    }

    public List<ScanRunRow> getRows() {
        return rows;
    }
}
