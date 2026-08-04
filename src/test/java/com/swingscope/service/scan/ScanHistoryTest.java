package com.swingscope.service.scan;

import com.swingscope.domain.candidate.CandidateRow;
import com.swingscope.domain.marketdata.MarketSnapshot;
import com.swingscope.domain.scan.ScanJob;
import com.swingscope.domain.scan.ScanResult;
import com.swingscope.domain.scan.ScanRun;
import com.swingscope.domain.scan.Tier;
import com.swingscope.repository.ScanRunRepository;
import com.swingscope.service.marketdata.MarketDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Stored scans and their weekly purge.
 *
 * <p>The point of persistence is not convenience — it is that a scan which exists only in memory can
 * never be checked afterwards. These assert the trail is faithful to what the tool said at the time.
 */
@SpringBootTest
@ActiveProfiles("test")
class ScanHistoryTest {

    @Autowired
    private ScanJobService scanJobs;

    @Autowired
    private ScanRunRepository runs;

    @Autowired
    private ScanHistoryPurgeService purge;

    @MockBean
    private MarketDataService marketData;

    @BeforeEach
    void stubProviderAndClear() {
        runs.deleteAll();
        when(marketData.getSnapshot(anyString(), anyBoolean())).thenAnswer(invocation -> {
            String symbol = invocation.getArgument(0);
            return new MarketSnapshot(symbol, new BigDecimal("40.00"), new BigDecimal("1.20"),
                    new BigDecimal("39.50"), new BigDecimal("38.00"), new BigDecimal("35.00"),
                    5_000_000L, 5_000_000L, new BigDecimal("3000000"), null,
                    true, false, false, 250, List.of());
        });
    }

    private String runScan(String... tickers) {
        ScanJob job = scanJobs.submit(List.of(tickers));
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (scanJobs.find(job.getId()).filter(j -> !j.isRunning()).isPresent()) {
                return job.getId();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("scan did not finish");
    }

    @Test
    @DisplayName("a finished scan is written to the database")
    void completedScansArePersisted() {
        String id = runScan("AAPL", "MSFT");

        ScanRun stored = runs.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ScanJob.Status.COMPLETE);
        assertThat(stored.getRequested()).isEqualTo(2);
        assertThat(stored.getRows()).hasSize(2);
        assertThat(stored.tickerList()).containsExactly("AAPL", "MSFT");
    }

    @Test
    @DisplayName("a stored scan rebuilds into the same view a live one produces")
    void storedScansRebuildFaithfully() {
        String id = runScan("AAPL", "MSFT");
        ScanResult live = scanJobs.find(id).orElseThrow().getResult();

        ScanResult rebuilt = runs.findById(id).orElseThrow().toResult();

        assertThat(rebuilt.requested()).isEqualTo(live.requested());
        assertThat(rebuilt.stocks()).hasSameSizeAs(live.stocks());
        assertThat(rebuilt.count(Tier.TIER1)).isEqualTo(live.count(Tier.TIER1));
        assertThat(rebuilt.stocks().get(0).symbol()).isEqualTo(live.stocks().get(0).symbol());
        assertThat(rebuilt.stocks().get(0).reason()).isEqualTo(live.stocks().get(0).reason());
        assertThat(rebuilt.stocks().get(0).price())
                .isEqualByComparingTo(live.stocks().get(0).price());
    }

    @Test
    @DisplayName("Phase 8: the auto-analysis survives the round trip, so a reloaded scan is not blank")
    void storedScansKeepTheirAnalysis() {
        String id = runScan("AAPL", "MSFT");
        ScanResult live = scanJobs.find(id).orElseThrow().getResult();
        assertThat(live.candidates()).isNotEmpty();

        ScanResult rebuilt = runs.findById(id).orElseThrow().toResult();

        assertThat(rebuilt.candidates()).hasSameSizeAs(live.candidates());
        assertThat(rebuilt.passCount()).isEqualTo(live.passCount());
        assertThat(rebuilt.needsLevelsCount()).isEqualTo(live.needsLevelsCount());

        CandidateRow before = live.candidates().get(0);
        CandidateRow after = rebuilt.candidates().get(0);
        assertThat(after.symbol()).isEqualTo(before.symbol());
        assertThat(after.tier()).isEqualTo(before.tier());
        assertThat(after.verdict()).isEqualTo(before.verdict());
        assertThat(after.grade()).isEqualTo(before.grade());
        assertThat(after.confidenceMet()).isEqualTo(before.confidenceMet());
        assertThat(after.confidenceTotal()).isEqualTo(before.confidenceTotal());
        assertThat(after.confidenceDetail()).isEqualTo(before.confidenceDetail());
        // The reasoning text is the point of keeping the row at all.
        assertThat(after.needed()).isEqualTo(before.needed());
        assertThat(after.weaknesses()).isEqualTo(before.weaknesses());
        assertThat(after.failReason()).isEqualTo(before.failReason());
    }

    @Test
    @DisplayName("rows that were never analysed restore as null rather than as an empty verdict")
    void unanalysedRowsRestoreAsAbsent() {
        // A SKIP row is not a candidate, so nothing was ever computed for it.
        when(marketData.getSnapshot(anyString(), anyBoolean())).thenAnswer(invocation ->
                new MarketSnapshot(invocation.getArgument(0), new BigDecimal("30.00"),
                        new BigDecimal("-1.00"), new BigDecimal("31.00"), new BigDecimal("32.00"),
                        new BigDecimal("35.00"), 5_000_000L, 5_000_000L,
                        new BigDecimal("3000000"), null, false, false, false, 250, List.of()));

        String id = runScan("DOWN");
        ScanRun stored = runs.findById(id).orElseThrow();

        assertThat(stored.getRows()).hasSize(1);
        assertThat(stored.getRows().get(0).toCandidateRow()).isNull();
        assertThat(stored.toResult().candidates()).isEmpty();
    }

    @Test
    @DisplayName("the row keeps what the tool said at the time, not what it would say now")
    void storedRowsAreASnapshot() {
        String id = runScan("AAPL");
        ScanRun stored = runs.findById(id).orElseThrow();

        // The provider now reports a very different picture.
        when(marketData.getSnapshot(anyString(), anyBoolean())).thenAnswer(invocation ->
                new MarketSnapshot("AAPL", new BigDecimal("5.00"), new BigDecimal("-40.00"),
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1L, 1L,
                        BigDecimal.ONE, null, false, true, false, 250, List.of()));

        ScanRun reread = runs.findById(id).orElseThrow();
        assertThat(reread.toResult().stocks().get(0).price()).isEqualByComparingTo("40.00");
        assertThat(reread.getRows().get(0).getTier()).isEqualTo(stored.getRows().get(0).getTier());
    }

    @Test
    @DisplayName("history outlives the in-memory job — that is the whole point")
    void findFallsBackToStorage() {
        String id = runScan("AAPL");

        // Simulate the job map being empty, as it would be after a restart.
        ScanJob restored = ScanJob.restored(runs.findById(id).orElseThrow());

        assertThat(restored.getId()).isEqualTo(id);
        assertThat(restored.isRunning()).isFalse();
        assertThat(restored.isStored()).isTrue();
        assertThat(restored.getResult().stocks()).isNotEmpty();
    }

    @Test
    void recentListsStoredScansNewestFirst() {
        runScan("AAPL");
        String second = runScan("MSFT");

        List<ScanJob> recent = scanJobs.recent();

        assertThat(recent).hasSizeGreaterThanOrEqualTo(2);
        assertThat(recent.get(0).getId()).isEqualTo(second);
    }

    // ------------------------------------------------------------------------------- purging

    @Test
    @DisplayName("the purge removes scans past the retention window and keeps the rest")
    void purgeRemovesOnlyOldScans() {
        String recent = runScan("AAPL");

        // An old scan, backdated well past the 30-day default.
        ScanRun ancient = new ScanRun("old12345", List.of("OLD"),
                Instant.now().minus(90, ChronoUnit.DAYS));
        ancient.fail("stale", Instant.now().minus(90, ChronoUnit.DAYS));
        runs.save(ancient);
        assertThat(runs.findById("old12345")).isPresent();

        int deleted = purge.purge();

        assertThat(deleted).isEqualTo(1);
        assertThat(runs.findById("old12345")).isEmpty();
        assertThat(runs.findById(recent)).as("recent scans survive").isPresent();
    }

    @Test
    void purgingWithNothingOldIsANoOp() {
        runScan("AAPL");

        assertThat(purge.purge()).isZero();
        assertThat(runs.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a provider error for one ticker degrades that row, and the scan still stores")
    void aProviderErrorForOneTickerIsRecordedAsUnavailable() {
        when(marketData.getSnapshot(anyString(), anyBoolean()))
                .thenThrow(new com.swingscope.service.marketdata.UnknownSymbolException(
                        "twelvedata", "AAPL"));

        String id = runScan("AAPL");

        ScanRun stored = runs.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ScanJob.Status.COMPLETE);
        assertThat(stored.getRows()).hasSize(1);
        assertThat(stored.getRows().get(0).getTier()).isEqualTo(Tier.UNAVAILABLE);
    }

    @Test
    @DisplayName("an unexpected failure is stored as FAILED — 'it broke last Tuesday' stays answerable")
    void unexpectedFailuresArePersisted() {
        // Not a MarketDataException, so it is not a per-ticker degradation — the run dies.
        when(marketData.getSnapshot(anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("provider exploded"));

        String id = runScan("AAPL");

        ScanRun stored = runs.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ScanJob.Status.FAILED);
        assertThat(stored.getError()).contains("provider exploded");
        assertThat(stored.getFinishedAt()).isNotNull();
    }
}
