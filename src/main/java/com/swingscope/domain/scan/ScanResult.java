package com.swingscope.domain.scan;

import java.util.List;
import java.util.Map;

/**
 * The outcome of scanning a pasted ticker list.
 *
 * @param byTier   candidates grouped by tier, so the UI can render Tier 1 first
 * @param elapsedMillis wall-clock time, which on a cold scan is mostly rate-limit pacing
 * @param warnings anything the user should know, e.g. tickers dropped as duplicates
 */
public record ScanResult(
        List<TieredStock> stocks,
        Map<Tier, List<TieredStock>> byTier,
        int requested,
        long elapsedMillis,
        List<String> warnings
) {

    public ScanResult {
        stocks = List.copyOf(stocks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public int count(Tier tier) {
        return byTier.getOrDefault(tier, List.of()).size();
    }

    /** Tier 1 and 2 — the names actually worth charting. */
    public List<TieredStock> tradeable() {
        return stocks.stream().filter(s -> s.tier().isTradeable()).toList();
    }
}
