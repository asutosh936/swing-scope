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
        List<String> warnings,
        /**
         * Phase 8: the full analysis for each tradeable candidate, best-founded first. Empty when
         * auto-analysis is off — the tiering above is unaffected either way.
         */
        List<com.swingscope.domain.candidate.CandidateRow> candidates
) {

    public ScanResult {
        stocks = List.copyOf(stocks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** The same result with candidate analyses attached. */
    public ScanResult withCandidates(
            List<com.swingscope.domain.candidate.CandidateRow> analysed) {
        return new ScanResult(stocks, byTier, requested, elapsedMillis, warnings, analysed);
    }

    /**
     * Groups and orders a set of tiered stocks. Used both by a live scan and when rebuilding one
     * from the database, so a stored scan renders through exactly the same code as a fresh one.
     */
    public static ScanResult of(java.util.List<TieredStock> stocks, int requested,
                                long elapsedMillis, java.util.List<String> warnings) {
        java.util.List<TieredStock> sorted = new java.util.ArrayList<>(stocks);
        sorted.sort(java.util.Comparator
                .comparing((TieredStock s) -> s.tier().ordinal())
                .thenComparing(s -> s.distanceToEma50Percent() == null
                                ? java.math.BigDecimal.ZERO : s.distanceToEma50Percent(),
                        java.util.Comparator.reverseOrder()));

        Map<Tier, List<TieredStock>> byTier = sorted.stream()
                .collect(java.util.stream.Collectors.groupingBy(TieredStock::tier,
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));

        return new ScanResult(sorted, byTier, requested, elapsedMillis, warnings, List.of());
    }

    public int count(Tier tier) {
        return byTier.getOrDefault(tier, List.of()).size();
    }

    /** Tier 1 and 2 — the names actually worth charting. */
    public List<TieredStock> tradeable() {
        return stocks.stream().filter(s -> s.tier().isTradeable()).toList();
    }

    /**
     * The analysed candidates in one tier, best-founded first (Phase 8). Empty when auto-analysis
     * is off or the scan predates it, in which case the view falls back to the plain tier table.
     */
    public List<com.swingscope.domain.candidate.CandidateRow> candidatesFor(Tier tier) {
        return candidates.stream().filter(c -> c.tier() == tier).toList();
    }

    /** How many analysed candidates cleared the ratio and sizing rules. */
    public long passCount() {
        return candidates.stream()
                .filter(c -> c.verdict() == com.swingscope.domain.candidate.CandidateVerdict.PASS)
                .count();
    }

    /** How many need a human to read the chart because the engine would not guess. */
    public long needsLevelsCount() {
        return candidates.stream()
                .filter(c -> c.verdict()
                        == com.swingscope.domain.candidate.CandidateVerdict.NEEDS_LEVELS)
                .count();
    }
}
