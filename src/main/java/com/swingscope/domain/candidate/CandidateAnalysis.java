package com.swingscope.domain.candidate;

import com.swingscope.domain.TradeAnalysis;
import com.swingscope.domain.levels.LevelAnalysis;
import com.swingscope.domain.scan.TieredStock;

import java.math.BigDecimal;
import java.util.List;

/**
 * One scanned candidate carried all the way through: tier, proposed levels, sizing verdict, and how
 * well-founded the whole thing is.
 *
 * @param analysis   null when the verdict is {@link CandidateVerdict#NEEDS_LEVELS}
 * @param needed     what the human must supply, when anything is missing
 */
public record CandidateAnalysis(
        TieredStock stock,
        LevelAnalysis levels,
        TradeAnalysis analysis,
        AnalysisConfidence confidence,
        CandidateVerdict verdict,
        List<String> needed
) {

    public CandidateAnalysis {
        needed = needed == null ? List.of() : List.copyOf(needed);
    }

    public String symbol() {
        return stock.symbol();
    }

    public BigDecimal entry() {
        return stock.price();
    }

    public BigDecimal stop() {
        return levels != null && levels.stop().isPresent() ? levels.stop().value() : null;
    }

    public BigDecimal target() {
        return levels != null && levels.target().isPresent() ? levels.target().value() : null;
    }

    public BigDecimal ratio() {
        return analysis == null ? null : analysis.ratio();
    }

    public Integer shares() {
        return analysis == null ? null : analysis.wholeShares();
    }

    /** True when either level came from the volatility fallback rather than price structure. */
    public boolean usesFallbackLevel() {
        return levels != null
                && ((levels.stop().isPresent() && levels.stop().isFallback())
                || (levels.target().isPresent() && levels.target().isFallback()));
    }
}
