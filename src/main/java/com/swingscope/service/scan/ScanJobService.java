package com.swingscope.service.scan;

import com.swingscope.domain.scan.ScanJob;
import com.swingscope.domain.scan.ScanResult;
import com.swingscope.domain.scan.ScanRun;
import com.swingscope.repository.ScanRunRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs scans off the request thread and keeps finished ones addressable.
 *
 * <p>Two problems, one mechanism. A paced scan takes minutes, so it cannot block an HTTP request;
 * and results that live only in a request's model are lost the moment you click through to the
 * calculator. Giving every scan an id and a home solves both.
 *
 * <p><strong>Single worker thread on purpose.</strong> Concurrent scans would contend for the same
 * provider rate limiter and simply interleave their waits, finishing no sooner while making progress
 * reporting meaningless. Queuing them is both simpler and more honest about what the free tier
 * allows.
 *
 * <p><strong>In-flight in memory, finished in the database.</strong> A running job's progress
 * changes every few seconds and is worth nothing after the fact, so it lives in a map. The moment it
 * finishes it is written to {@code scan_run}, which is what survives a restart and what the weekly
 * purge later trims.
 */
@Service
public class ScanJobService {

    private static final Logger log = LoggerFactory.getLogger(ScanJobService.class);

    /** Enough to click away and back several times; old scans are stale anyway. */
    public static final int MAX_RETAINED_JOBS = 20;

    private final TierService tierService;
    private final ScanRunRepository runs;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "scan-worker");
                thread.setDaemon(true);
                return thread;
            });

    /** Access-ordered so eviction drops the least recently *viewed* job, not the oldest. */
    private final Map<String, ScanJob> jobs = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ScanJob> eldest) {
            return size() > MAX_RETAINED_JOBS;
        }
    };

    public ScanJobService(TierService tierService, ScanRunRepository runs) {
        this.tierService = tierService;
        this.runs = runs;
    }

    /** Queues a scan and returns immediately with its id. */
    public ScanJob submit(List<String> tickers) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        ScanJob job = new ScanJob(id, tickers);
        synchronized (jobs) {
            jobs.put(id, job);
        }
        log.info("Scan job {} queued for {} ticker(s): {}", id, job.getTotal(), job.getTickers());

        executor.submit(() -> run(job));
        return job;
    }

    private void run(ScanJob job) {
        ScanRun record = new ScanRun(job.getId(), job.getTickers(), job.getStartedAt());
        try {
            ScanResult result = tierService.scan(job.getTickers(), job::progress);
            // Store BEFORE flipping the job to complete. Anything that reacts to completion — a
            // redirect to the results page, a test awaiting the worker — must find the record
            // already durable, or it races the write.
            record.complete(result, Instant.now());
            persist(record);
            job.complete(result);
            log.info("Scan job {} complete in {}s and stored", job.getId(), job.getElapsedSeconds());
        } catch (RuntimeException e) {
            // A scan that dies must say so on its own page rather than vanishing — and the failure
            // is stored too, so "it broke last Tuesday" stays answerable.
            log.error("Scan job {} failed: {}", job.getId(), e.getMessage(), e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            record.fail(message, Instant.now());
            persist(record);
            job.fail(message);
        }
    }

    /** Storage must never take the scan down with it — the result is already computed. */
    private void persist(ScanRun record) {
        try {
            runs.save(record);
        } catch (RuntimeException e) {
            log.error("Could not store scan {} — the results are still viewable until restart: {}",
                    record.getId(), e.getMessage(), e);
        }
    }

    /** Live job if one is running, otherwise the stored run. */
    public Optional<ScanJob> find(String id) {
        synchronized (jobs) {
            ScanJob inMemory = jobs.get(id);
            if (inMemory != null) {
                return Optional.of(inMemory);
            }
        }
        return runs.findById(id).map(ScanJob::restored);
    }

    /**
     * Most recent first: anything still running, then the stored history. Nothing is more than a
     * click away, and the list survives a restart.
     */
    public List<ScanJob> recent() {
        List<ScanJob> running;
        synchronized (jobs) {
            running = jobs.values().stream().filter(ScanJob::isRunning).toList();
        }
        List<ScanJob> stored = runs.findAllByOrderByStartedAtDesc().stream()
                .map(ScanJob::restored)
                .filter(j -> running.stream().noneMatch(r -> r.getId().equals(j.getId())))
                .toList();

        List<ScanJob> all = new ArrayList<>(running);
        all.addAll(stored);
        return List.copyOf(all);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Scan worker did not stop within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
