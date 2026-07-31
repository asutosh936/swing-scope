package com.swingscope.service.scan;

import com.swingscope.config.ScanProperties;
import com.swingscope.domain.marketdata.MarketSnapshot;
import com.swingscope.domain.scan.ScanResult;
import com.swingscope.domain.scan.Tier;
import com.swingscope.domain.scan.TieredStock;
import com.swingscope.service.marketdata.MarketDataService;
import com.swingscope.service.marketdata.UnknownSymbolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TierServiceTest {

    private MarketDataService marketData;
    private TierService tierService;

    /** Records which symbols were asked for fundamentals, to prove the short-circuit works. */
    private final Map<String, MarketSnapshot> trendOnly = new HashMap<>();
    private final Map<String, MarketSnapshot> full = new HashMap<>();
    private final List<String> fundamentalCalls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        marketData = mock(MarketDataService.class);
        tierService = new TierService(marketData, new ScanProperties(null, null, null, null));

        when(marketData.getSnapshot(anyString(), anyBoolean())).thenAnswer(inv -> {
            String symbol = inv.getArgument(0);
            boolean withFundamentals = inv.getArgument(1);
            if (withFundamentals) {
                fundamentalCalls.add(symbol);
                return full.get(symbol);
            }
            return trendOnly.containsKey(symbol) ? trendOnly.get(symbol) : full.get(symbol);
        });
    }

    /** Builds a snapshot; {@code inUptrend} null means "not enough history". */
    private static MarketSnapshot snapshot(String symbol, String price, String changePct,
                                           Boolean uptrend, Long volume, String capMillions,
                                           LocalDate earnings) {
        BigDecimal p = new BigDecimal(price);
        boolean bigMover = changePct != null
                && new BigDecimal(changePct).abs().compareTo(new BigDecimal("5")) > 0;
        boolean earningsSoon = earnings != null
                && !earnings.isBefore(LocalDate.now())
                && !earnings.isAfter(LocalDate.now().plusDays(3));
        return new MarketSnapshot(symbol, p,
                changePct == null ? null : new BigDecimal(changePct),
                new BigDecimal("39.00"), new BigDecimal("38.00"), new BigDecimal("35.00"),
                volume, volume, capMillions == null ? null : new BigDecimal(capMillions),
                earnings, uptrend, bigMover, earningsSoon, 250, List.of());
    }

    /** Snapshot with today's partial volume and the average deliberately different. */
    private static MarketSnapshot snapshot(String symbol, String price, Long todaysVolume,
                                           Long averageVolume, String capMillions) {
        return new MarketSnapshot(symbol, new BigDecimal(price), new BigDecimal("1.0"),
                new BigDecimal("39.00"), new BigDecimal("38.00"), new BigDecimal("35.00"),
                todaysVolume, averageVolume, new BigDecimal(capMillions),
                null, true, false, false, 250, List.of());
    }

    private void given(String symbol, MarketSnapshot s) {
        trendOnly.put(symbol, s);
        full.put(symbol, s);
    }

    // ---------------------------------------------------------------------------- ticker parsing

    @Test
    @DisplayName("a pasted list splits on commas, spaces and newlines alike")
    void parsesAnyPastedShape() {
        assertThat(TierService.parseTickers("VZ, CARR AAPL\nMSFT;NVDA"))
                .containsExactly("VZ", "CARR", "AAPL", "MSFT", "NVDA");
    }

    @Test
    void parsingUppercasesAndDeduplicates() {
        assertThat(TierService.parseTickers("vz, VZ, carr")).containsExactly("VZ", "CARR");
    }

    @Test
    void parsingHandlesNullAndBlank() {
        assertThat(TierService.parseTickers(null)).isEmpty();
        assertThat(TierService.parseTickers("   ")).isEmpty();
    }

    // ------------------------------------------------------------------------------- the rules

    @Test
    @DisplayName("liquid, established, trend intact → Tier 1")
    void tier1NeedsVolumeAndMarketCap() {
        given("AAPL", snapshot("40.00", "40.00", "1.2", true, 5_000_000L, "3000000", null));
        given("AAPL", snapshot("AAPL", "40.00", "1.2", true, 5_000_000L, "3000000", null));

        TieredStock result = tierService.tierOne("AAPL");

        assertThat(result.tier()).isEqualTo(Tier.TIER1);
        assertThat(result.reason()).isEqualTo("trend intact, liquid and established");
    }

    @Test
    @DisplayName("thin volume drops an otherwise fine name to Tier 2")
    void thinVolumeIsTier2() {
        given("THIN", snapshot("THIN", "40.00", "1.2", true, 250_000L, "3000000", null));

        TieredStock result = tierService.tierOne("THIN");

        assertThat(result.tier()).isEqualTo(Tier.TIER2);
        assertThat(result.reason()).contains("thin").contains("250,000");
    }

    @Test
    @DisplayName("a small cap drops to Tier 2 even when it trades plenty")
    void smallCapIsTier2() {
        given("SMALL", snapshot("SMALL", "40.00", "1.2", true, 5_000_000L, "800", null));

        TieredStock result = tierService.tierOne("SMALL");

        assertThat(result.tier()).isEqualTo(Tier.TIER2);
        assertThat(result.reason()).contains("small").contains("$0.8B");
    }

    @Test
    @DisplayName("market cap is compared in MILLIONS — $2B is 2000, not 2000000000")
    void marketCapThresholdIsInMillions() {
        // 2500 million = $2.5B, comfortably over the $2B bar.
        given("MID", snapshot("MID", "40.00", "1.2", true, 5_000_000L, "2500", null));
        assertThat(tierService.tierOne("MID").tier()).isEqualTo(Tier.TIER1);

        // 1500 million = $1.5B, under it.
        given("SUB", snapshot("SUB", "40.00", "1.2", true, 5_000_000L, "1500", null));
        assertThat(tierService.tierOne("SUB").tier()).isEqualTo(Tier.TIER2);
    }

    @Test
    @DisplayName("a big move today is Tier 3 news risk, whatever the liquidity")
    void bigMoverIsTier3() {
        given("POP", snapshot("POP", "40.00", "9.3", true, 5_000_000L, "3000000", null));

        TieredStock result = tierService.tierOne("POP");

        assertThat(result.tier()).isEqualTo(Tier.TIER3);
        assertThat(result.reason()).isEqualTo("up 9.3% today — news risk");
    }

    @Test
    void aBigDropIsAlsoTier3() {
        given("DROP", snapshot("DROP", "40.00", "-7.10", true, 5_000_000L, "3000000", null));

        // trailing zeros are stripped: "7.1%" reads better than "7.10%"
        assertThat(tierService.tierOne("DROP").reason()).isEqualTo("down 7.1% today — news risk");
    }

    @Test
    @DisplayName("earnings inside 3 days outrank a big move — the block window comes first")
    void earningsWithinThreeDaysIsTier3() {
        given("ERN", snapshot("ERN", "40.00", "9.9", true, 5_000_000L, "3000000",
                LocalDate.now().plusDays(2)));

        TieredStock result = tierService.tierOne("ERN");

        assertThat(result.tier()).isEqualTo(Tier.TIER3);
        assertThat(result.reason()).isEqualTo("earnings in 2 days");
    }

    @Test
    void earningsTodayReadsNaturally() {
        given("TDY", snapshot("TDY", "40.00", "1.0", true, 5_000_000L, "3000000", LocalDate.now()));
        assertThat(tierService.tierOne("TDY").reason()).isEqualTo("earnings today — event risk");

        given("TMR", snapshot("TMR", "40.00", "1.0", true, 5_000_000L, "3000000",
                LocalDate.now().plusDays(1)));
        assertThat(tierService.tierOne("TMR").reason()).isEqualTo("earnings in 1 day");
    }

    @Test
    @DisplayName("earnings further out doesn't tier down")
    void distantEarningsIsFine() {
        given("FAR", snapshot("FAR", "40.00", "1.0", true, 5_000_000L, "3000000",
                LocalDate.now().plusDays(20)));

        assertThat(tierService.tierOne("FAR").tier()).isEqualTo(Tier.TIER1);
    }

    @Test
    void failingTheTrendTestIsSkip() {
        given("DOWN", snapshot("DOWN", "30.00", "1.0", false, 5_000_000L, "3000000", null));

        TieredStock result = tierService.tierOne("DOWN");

        assertThat(result.tier()).isEqualTo(Tier.SKIP);
        assertThat(result.reason()).isEqualTo("below the 50-EMA");
    }

    @Test
    @DisplayName("null inUptrend means too little history — SKIP with a reason, never treated as false")
    void inconclusiveTrendIsSkipWithItsOwnReason() {
        given("NEW", snapshot("NEW", "40.00", "1.0", null, 5_000_000L, "3000000", null));

        TieredStock result = tierService.tierOne("NEW");

        assertThat(result.tier()).isEqualTo(Tier.SKIP);
        assertThat(result.reason()).contains("not enough history").contains("inconclusive");
    }

    // -------------------------------------------------------------------------- short-circuiting

    @Test
    @DisplayName("a name that fails the trend test never costs a fundamentals call")
    void skipDoesNotFetchFundamentals() {
        given("DOWN", snapshot("DOWN", "30.00", "1.0", false, 5_000_000L, "3000000", null));

        tierService.tierOne("DOWN");

        assertThat(fundamentalCalls).isEmpty();
        verify(marketData, never()).getSnapshot("DOWN", true);
    }

    @Test
    void aTrendingNameDoesFetchFundamentals() {
        given("AAPL", snapshot("AAPL", "40.00", "1.2", true, 5_000_000L, "3000000", null));

        tierService.tierOne("AAPL");

        assertThat(fundamentalCalls).containsExactly("AAPL");
    }

    // -------------------------------------------------------------------------------- scanning

    @Test
    void unavailableDataIsReportedNotGuessedAround() {
        when(marketData.getSnapshot("BAD", false))
                .thenThrow(new UnknownSymbolException("twelvedata", "BAD"));

        TieredStock result = tierService.tierOne("BAD");

        assertThat(result.tier()).isEqualTo(Tier.UNAVAILABLE);
        assertThat(result.reason()).contains("unknown symbol");
        assertThat(result.price()).isNull();
    }

    @Test
    @DisplayName("results come back grouped and sorted, best tier first")
    void scanGroupsAndSorts() {
        given("A", snapshot("A", "40.00", "1.0", true, 5_000_000L, "3000000", null));   // tier 1
        given("B", snapshot("B", "40.00", "1.0", true, 100_000L, "500", null));         // tier 2
        given("C", snapshot("C", "30.00", "1.0", false, 5_000_000L, "3000000", null));  // skip

        ScanResult result = tierService.scan(List.of("A", "B", "C"));

        assertThat(result.stocks()).extracting(TieredStock::symbol).containsExactly("A", "B", "C");
        assertThat(result.count(Tier.TIER1)).isEqualTo(1);
        assertThat(result.count(Tier.TIER2)).isEqualTo(1);
        assertThat(result.count(Tier.SKIP)).isEqualTo(1);
        assertThat(result.tradeable()).extracting(TieredStock::symbol).containsExactly("A", "B");
        assertThat(result.requested()).isEqualTo(3);
    }

    @Test
    void scanDeduplicatesAndWarns() {
        given("A", snapshot("A", "40.00", "1.0", true, 5_000_000L, "3000000", null));

        ScanResult result = tierService.scan(List.of("A", "a", " A "));

        assertThat(result.stocks()).hasSize(1);
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("duplicate"));
    }

    @Test
    @DisplayName("an over-long list is truncated with a warning rather than running for an hour")
    void scanRespectsTheBatchCeiling() {
        TierService limited = new TierService(marketData, new ScanProperties(null, null, 2, null));
        given("A", snapshot("A", "40.00", "1.0", true, 5_000_000L, "3000000", null));
        given("B", snapshot("B", "40.00", "1.0", true, 5_000_000L, "3000000", null));
        given("C", snapshot("C", "40.00", "1.0", true, 5_000_000L, "3000000", null));

        ScanResult result = limited.scan(List.of("A", "B", "C"));

        assertThat(result.stocks()).hasSize(2);
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("truncated"));
    }

    @Test
    void anEmptyListScansToNothing() {
        ScanResult result = tierService.scan(List.of());

        assertThat(result.stocks()).isEmpty();
        assertThat(result.requested()).isZero();
    }

    @Test
    @DisplayName("liquidity uses AVERAGE volume — a mega-cap mid-session is not 'thin'")
    void liquidityUsesAverageVolumeNotTodays() {
        // COST mid-morning: 169k traded so far today against a ~2M daily average.
        given("COST", snapshot("COST", "971.96", 169_476L, 2_100_000L, "428660"));

        TieredStock result = tierService.tierOne("COST");

        assertThat(result.tier()).isEqualTo(Tier.TIER1);
        assertThat(result.reason()).isEqualTo("trend intact, liquid and established");
        assertThat(result.averageVolume()).isEqualTo(2_100_000L);
        assertThat(result.volume()).isEqualTo(169_476L);   // today's is still reported
    }

    @Test
    @DisplayName("a genuinely thin name is still Tier 2, and the reason says 'avg'")
    void aGenuinelyThinNameIsStillTier2() {
        given("THIN", snapshot("THIN", "58.58", 311_968L, 420_000L, "3540"));

        TieredStock result = tierService.tierOne("THIN");

        assertThat(result.tier()).isEqualTo(Tier.TIER2);
        assertThat(result.reason()).isEqualTo(
                "trend intact but thin — 420,000 avg shares/day vs 1,000,000 needed");
    }

    @Test
    @DisplayName("with no average available, today's volume is the fallback")
    void fallsBackToTodaysVolumeWhenNoAverageIsReported() {
        given("NOAVG", snapshot("NOAVG", "40.00", 5_000_000L, null, "3000000"));

        assertThat(tierService.tierOne("NOAVG").tier()).isEqualTo(Tier.TIER1);
    }

    @Test
    @DisplayName("change% is rounded for display rather than shown at float precision")
    void changePercentIsRoundedForDisplay() {
        given("RAW", new MarketSnapshot("RAW", new BigDecimal("58.575"),
                new BigDecimal("-0.21294849"),
                new BigDecimal("39.00"), new BigDecimal("38.00"), new BigDecimal("35.00"),
                5_000_000L, 5_000_000L, new BigDecimal("3540"), null, true, false, false,
                250, List.of()));

        assertThat(tierService.tierOne("RAW").changePercent()).isEqualByComparingTo("-0.21");
    }

    @Test
    void distanceToEma50IsComputedForDisplay() {
        given("A", snapshot("A", "40.00", "1.0", true, 5_000_000L, "3000000", null));

        // price 40.00 against a 38.00 EMA50 = +5.26%
        assertThat(tierService.tierOne("A").distanceToEma50Percent()).isEqualByComparingTo("5.26");
    }

    @Test
    void marketCapConvertsToBillionsForDisplay() {
        given("A", snapshot("A", "40.00", "1.0", true, 5_000_000L, "3250000", null));

        assertThat(tierService.tierOne("A").marketCapBillions()).isEqualByComparingTo("3250.00");
    }
}
