package com.swingscope.domain.scan;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A scan running in the background.
 *
 * <p>Scans cannot be synchronous. Twelve Data's free tier allows 8 calls a minute and each ticker
 * costs two, so a 20-name list needs five minutes of paced fetching — far past any browser or proxy
 * timeout. The request starts a job and returns immediately; the browser polls a stable URL.
 *
 * <p>That stable URL is also what makes results survive navigation: clicking "Plan this trade" and
 * coming back re-renders the finished job instead of re-running it.
 *
 * <p>Mutable and shared across threads, so every field is guarded.
 */
public class ScanJob {

    public enum Status {
        RUNNING,
        COMPLETE,
        FAILED
    }

    private final String id;
    private final List<String> tickers;
    private final Instant startedAt = Instant.now();
    /** Set only on a restored job, so elapsed time reflects the original run. */
    private Instant restoredStartedAt;

    private volatile Status status = Status.RUNNING;
    private volatile int completed;
    private volatile String currentSymbol;
    private volatile ScanResult result;
    private volatile String error;
    private volatile Instant finishedAt;

    public ScanJob(String id, List<String> tickers) {
        this.id = id;
        this.tickers = List.copyOf(tickers);
    }

    /**
     * A finished scan read back from the database, presented as a job so the page renders through
     * one code path whether the scan just ran or ran last week.
     */
    public static ScanJob restored(ScanRun run) {
        ScanJob job = new ScanJob(run.getId(), run.tickerList());
        job.status = run.getStatus();
        job.completed = run.getRequested();
        job.finishedAt = run.getFinishedAt();
        job.error = run.getError();
        job.result = run.getStatus() == Status.COMPLETE ? run.toResult() : null;
        job.restoredStartedAt = run.getStartedAt();
        return job;
    }

    public String getId() {
        return id;
    }

    public Instant getStartedAt() {
        return restoredStartedAt != null ? restoredStartedAt : startedAt;
    }

    /** True when this came out of the database rather than the current process. */
    public boolean isStored() {
        return restoredStartedAt != null;
    }

    public List<String> getTickers() {
        return tickers;
    }

    public int getTotal() {
        return tickers.size();
    }

    public Status getStatus() {
        return status;
    }

    public int getCompleted() {
        return completed;
    }

    public String getCurrentSymbol() {
        return currentSymbol;
    }

    public ScanResult getResult() {
        return result;
    }

    public String getError() {
        return error;
    }

    public boolean isRunning() {
        return status == Status.RUNNING;
    }

    /** Percent complete, for the progress bar. */
    public int getPercent() {
        return getTotal() == 0 ? 100 : Math.min(100, completed * 100 / getTotal());
    }

    public long getElapsedSeconds() {
        Instant end = finishedAt == null ? Instant.now() : finishedAt;
        return Duration.between(getStartedAt(), end).toSeconds();
    }

    /**
     * Rough seconds remaining, from the pacing rate rather than observed speed — early estimates
     * from one or two samples would swing wildly.
     */
    public long getEstimatedSecondsRemaining(int callsPerTicker, int callsPerMinute) {
        if (!isRunning() || callsPerMinute <= 0) {
            return 0;
        }
        int remaining = Math.max(0, getTotal() - completed);
        return (long) remaining * callsPerTicker * 60 / callsPerMinute;
    }

    public void progress(String symbol, int done) {
        this.currentSymbol = symbol;
        this.completed = done;
    }

    public void complete(ScanResult result) {
        this.result = result;
        this.completed = getTotal();
        this.status = Status.COMPLETE;
        this.finishedAt = Instant.now();
    }

    public void fail(String message) {
        this.error = message;
        this.status = Status.FAILED;
        this.finishedAt = Instant.now();
    }
}
