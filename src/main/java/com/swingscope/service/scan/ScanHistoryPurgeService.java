package com.swingscope.service.scan;

import com.swingscope.config.ScanProperties;
import com.swingscope.repository.ScanRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Weekly trim of stored scans.
 *
 * <p>A scan is a snapshot of prices at one moment; a month later it is a curiosity, not data you
 * would act on. Keeping them forever would grow the file for no benefit — but deleting them the
 * same day would throw away the trail that makes "what did the tool actually say on the 3rd?"
 * answerable.
 *
 * <p>Runs Monday at 03:00 local. Retention is configurable via {@code scan.history-retention-days}
 * and defaults to 30 days.
 */
@Service
public class ScanHistoryPurgeService {

    private static final Logger log = LoggerFactory.getLogger(ScanHistoryPurgeService.class);

    private final ScanRunRepository runs;
    private final ScanProperties properties;

    public ScanHistoryPurgeService(ScanRunRepository runs, ScanProperties properties) {
        this.runs = runs;
        this.properties = properties;
        log.info("Scan history retention: {} days, purged weekly", properties.historyRetentionDays());
    }

    /** Monday 03:00 — after the weekend, before any Monday scanning. */
    @Scheduled(cron = "0 0 3 * * MON")
    @Transactional
    public void purgeOnSchedule() {
        purge();
    }

    /**
     * Deletes scans older than the retention window.
     *
     * @return how many were removed
     */
    @Transactional
    public int purge() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(properties.historyRetentionDays()));
        long due = runs.countByStartedAtBefore(cutoff);
        if (due == 0) {
            log.info("Scan purge: nothing older than {} ({} day retention)",
                    cutoff, properties.historyRetentionDays());
            return 0;
        }

        int deleted = runs.deleteOlderThan(cutoff);
        log.info("Scan purge: removed {} scan(s) started before {} ({} day retention)",
                deleted, cutoff, properties.historyRetentionDays());
        return deleted;
    }
}
