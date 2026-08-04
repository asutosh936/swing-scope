package com.swingscope.domain.candidate;

import java.math.BigDecimal;
import java.util.List;

/**
 * One analysed candidate, flattened to exactly what the results page shows.
 *
 * <p>Exists because a scan outlives the process that ran it. {@link CandidateAnalysis} composes the
 * live level and trade analyses; those are expensive to rebuild and would need provider calls after
 * a restart, when the candle cache is cold. Storing this projection instead means a scan reloaded
 * from the database renders through the same template as a fresh one, with no network at all.
 *
 * <p>The strings here are display text, deliberately. Reconstituting the reasoning objects would
 * invite re-deriving numbers that were computed at scan time against data that has since moved.
 * What was decided then is what the row should keep saying.
 */
public record CandidateRow(
        String symbol,
        com.swingscope.domain.scan.Tier tier,
        BigDecimal entry,
        BigDecimal stop,
        BigDecimal target,
        BigDecimal ratio,
        Integer shares,
        CandidateVerdict verdict,
        AnalysisConfidence.Grade grade,
        int confidenceMet,
        int confidenceTotal,
        /** Every factor, met or not — the tooltip behind the badge. */
        String confidenceDetail,
        /** The unmet factors, already formatted as "name (value)". */
        List<String> weaknesses,
        /** For NEEDS_LEVELS: the refusal reason verbatim, and the fields the human must supply. */
        List<String> needed,
        /** For FAIL: which rule broke. */
        String failReason
) {

    public CandidateRow {
        weaknesses = weaknesses == null ? List.of() : List.copyOf(weaknesses);
        needed = needed == null ? List.of() : List.copyOf(needed);
    }

    public static CandidateRow from(CandidateAnalysis c) {
        return new CandidateRow(
                c.stock().symbol(), c.stock().tier(),
                c.entry(), c.stop(), c.target(), c.ratio(), c.shares(),
                c.verdict(),
                c.confidence().grade(), c.confidence().met(), c.confidence().total(),
                c.confidence().summary(),
                c.confidence().weaknesses().stream()
                        .map(f -> f.name() + " (" + f.detail() + ")").toList(),
                c.needed(),
                c.analysis() == null ? null : c.analysis().reason());
    }

    public boolean needsLevels() {
        return verdict == CandidateVerdict.NEEDS_LEVELS;
    }

    public boolean failed() {
        return verdict == CandidateVerdict.FAIL;
    }
}
