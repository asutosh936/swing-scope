package com.swingscope.service.scan;

import com.swingscope.config.ScanProperties;
import com.swingscope.domain.marketdata.MarketSnapshot;
import com.swingscope.domain.scan.ScanResult;
import com.swingscope.domain.scan.Tier;
import com.swingscope.domain.scan.TieredStock;
import com.swingscope.service.marketdata.MarketDataException;
import com.swingscope.service.marketdata.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Applies the mechanical filters to a pasted ticker list and sorts the survivors into tiers.
 *
 * <p>Everything here is arithmetic on fetched facts. The tool decides <em>what is worth your chart
 * time</em>, never what to buy — and it never invents the two numbers it cannot know: the stop and
 * the target, which come from support and resistance the human reads off the chart.
 *
 * <p><strong>Order matters.</strong> The trend test runs first and short-circuits, so a name below
 * its 50-EMA costs two provider calls rather than four.
 */
@Service
public class TierService {

    private static final Logger log = LoggerFactory.getLogger(TierService.class);

    private final MarketDataService marketData;
    private final ScanProperties properties;

    public TierService(MarketDataService marketData, ScanProperties properties) {
        this.marketData = marketData;
        this.properties = properties;
        log.info("TierService thresholds: Tier 1 needs volume > {} AND market cap > {}M; "
                        + "max {} tickers per scan",
                properties.tier1MinVolume(), properties.tier1MinMarketCapMillions(),
                properties.maxTickersPerScan());
    }

    /**
     * Splits a pasted blob on commas, whitespace and newlines. Accepts whatever shape the list
     * arrives in from a screener, deduplicates, and preserves the order given.
     */
    public static List<String> parseTickers(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(
                java.util.Arrays.stream(raw.split("[,;\\s]+"))
                        .map(String::trim)
                        .filter(t -> !t.isEmpty())
                        .map(String::toUpperCase)
                        .toList()));
    }

    public ScanResult scan(List<String> tickers) {
        long startedAt = System.nanoTime();
        List<String> warnings = new ArrayList<>();

        List<String> unique = new ArrayList<>(new LinkedHashSet<>(
                tickers.stream().map(t -> t.trim().toUpperCase()).filter(t -> !t.isEmpty()).toList()));

        if (unique.size() < tickers.size()) {
            warnings.add("dropped %d duplicate or blank ticker(s)".formatted(tickers.size() - unique.size()));
        }

        int max = properties.maxTickersPerScan();
        if (unique.size() > max) {
            warnings.add("list truncated to the first %d tickers (limit is %d per scan)"
                    .formatted(max, max));
            log.warn("Scan list of {} truncated to {}", unique.size(), max);
            unique = unique.subList(0, max);
        }

        log.info("Scanning {} ticker(s): {}", unique.size(), unique);

        List<TieredStock> stocks = new ArrayList<>(unique.size());
        for (String symbol : unique) {
            stocks.add(tierOne(symbol));
        }

        // Tier order first, then strongest trend within a tier.
        stocks.sort(java.util.Comparator
                .comparing((TieredStock s) -> s.tier().ordinal())
                .thenComparing(s -> s.distanceToEma50Percent() == null
                        ? BigDecimal.ZERO : s.distanceToEma50Percent(),
                        java.util.Comparator.reverseOrder()));

        Map<Tier, List<TieredStock>> byTier = stocks.stream()
                .collect(Collectors.groupingBy(TieredStock::tier, LinkedHashMap::new, Collectors.toList()));

        long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
        ScanResult result = new ScanResult(stocks, byTier, unique.size(), elapsed, warnings);

        log.info("Scan complete in {}ms — Tier1={} Tier2={} Tier3={} Skip={} Unavailable={}",
                elapsed, result.count(Tier.TIER1), result.count(Tier.TIER2),
                result.count(Tier.TIER3), result.count(Tier.SKIP), result.count(Tier.UNAVAILABLE));
        return result;
    }

    /** One ticker through the filters. A data failure is reported, never guessed around. */
    public TieredStock tierOne(String symbol) {
        try {
            // Trend first, without fundamentals — a failure here ends the assessment.
            MarketSnapshot trend = marketData.getSnapshot(symbol, false);

            if (trend.inUptrend() == null) {
                return TieredStock.from(trend, Tier.SKIP,
                        "not enough history for the 200-EMA — trend test inconclusive");
            }
            if (!trend.inUptrend()) {
                return TieredStock.from(trend, Tier.SKIP, trendFailureReason(trend));
            }

            // Trend holds, so the fundamentals are now worth their two calls.
            MarketSnapshot full = marketData.getSnapshot(symbol, true);

            if (full.earningsWithin3Days()) {
                return TieredStock.from(full, Tier.TIER3, earningsReason(full));
            }
            if (full.bigMover()) {
                return TieredStock.from(full, Tier.TIER3,
                        "%s%s%% today — news risk".formatted(
                                full.changePercent().signum() > 0 ? "up " : "down ",
                                full.changePercent().abs()
                                        .setScale(2, java.math.RoundingMode.HALF_UP)
                                        .stripTrailingZeros().toPlainString()));
            }

            return TieredStock.from(full, tierByLiquidity(full), liquidityReason(full));

        } catch (MarketDataException e) {
            log.warn("Could not tier {} — {}", symbol, e.getMessage());
            return TieredStock.unavailable(symbol, e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------ rules

    /** Tier 1 needs both liquidity and size; either one missing drops it to Tier 2. */
    private Tier tierByLiquidity(MarketSnapshot snapshot) {
        return liquidEnough(snapshot) && bigEnough(snapshot) ? Tier.TIER1 : Tier.TIER2;
    }

    /**
     * Liquidity is judged on <strong>average</strong> daily volume, not today's.
     *
     * <p>Today's figure is a partial session while the market is open, so testing against it demotes
     * genuinely liquid names — a mega-cap mid-morning might show 170k shares against a 2M average.
     * Today's volume is only used as a fallback when the provider omits the average.
     */
    private boolean liquidEnough(MarketSnapshot snapshot) {
        Long liquidity = liquidityVolume(snapshot);
        return liquidity != null && liquidity > properties.tier1MinVolume();
    }

    private static Long liquidityVolume(MarketSnapshot snapshot) {
        return snapshot.averageVolume() != null ? snapshot.averageVolume() : snapshot.volume();
    }

    /**
     * Market cap arrives in <strong>millions</strong> from Finnhub, and the threshold is stored the
     * same way. Comparing against a dollar amount here would be wrong by a factor of a million.
     */
    private boolean bigEnough(MarketSnapshot snapshot) {
        return snapshot.marketCap() != null
                && snapshot.marketCap().compareTo(properties.tier1MinMarketCapMillions()) > 0;
    }

    private static String trendFailureReason(MarketSnapshot s) {
        if (s.price() != null && s.ema50() != null && s.price().compareTo(s.ema50()) <= 0) {
            return "below the 50-EMA";
        }
        return "50-EMA below the 200-EMA — no uptrend";
    }

    private static String earningsReason(MarketSnapshot s) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(
                java.time.LocalDate.now(), s.nextEarningsDate());
        if (days <= 0) {
            return "earnings today — event risk";
        }
        return "earnings in %d day%s".formatted(days, days == 1 ? "" : "s");
    }

    private String liquidityReason(MarketSnapshot s) {
        if (!liquidEnough(s)) {
            Long liquidity = liquidityVolume(s);
            return liquidity == null
                    ? "trend intact, volume unknown"
                    : "trend intact but thin — %,d avg shares/day vs %,d needed"
                            .formatted(liquidity, properties.tier1MinVolume());
        }
        if (!bigEnough(s)) {
            return s.marketCap() == null
                    ? "trend intact, market cap unknown"
                    : "trend intact but small — $%sB cap".formatted(
                            s.marketCap().divide(new BigDecimal("1000"), 1,
                                    java.math.RoundingMode.HALF_UP));
        }
        return "trend intact, liquid and established";
    }
}
