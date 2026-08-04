package com.swingscope.domain.candidate;

import java.util.List;

/**
 * How well-founded a candidate's analysis is — <strong>not</strong> how likely the trade is to win.
 *
 * <p>That distinction is the whole design. Every input below is a fact the app already holds about
 * the derivation: how much history it had, whether the levels came from structure or a fallback, how
 * complete the provider data was. None of them is evidence about future price, and there is
 * deliberately no field anywhere in this type that could express a probability of success.
 *
 * <p>A HIGH grade means "this row rests on solid inputs". It does not mean the method is validated —
 * the 6A.8 wide run found the structural levels no better than a volatility rule on a matched
 * comparison. Well-founded derivation and proven method are different claims.
 */
public record AnalysisConfidence(Grade grade, int met, List<Factor> factors) {

    public enum Grade {
        /** Five or six factors satisfied. */
        HIGH,
        /** Three or four. */
        MEDIUM,
        /** Two or fewer — read the factors before trusting anything here. */
        LOW
    }

    /**
     * @param met    whether this input is in good shape
     * @param detail the actual value, so the reader can judge rather than take the flag on trust
     */
    public record Factor(String name, boolean met, String detail) {
    }

    public AnalysisConfidence {
        factors = factors == null ? List.of() : List.copyOf(factors);
    }

    public static AnalysisConfidence of(List<Factor> factors) {
        int met = (int) factors.stream().filter(Factor::met).count();
        Grade grade = met >= 5 ? Grade.HIGH : met >= 3 ? Grade.MEDIUM : Grade.LOW;
        return new AnalysisConfidence(grade, met, factors);
    }

    public int total() {
        return factors.size();
    }

    /** Every factor, met or not, one per line — the detail behind the badge. */
    public String summary() {
        return factors.stream()
                .map(f -> (f.met() ? "✓ " : "✗ ") + f.name() + ": " + f.detail())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** The factors that are not in good shape — what to look at first. */
    public List<Factor> weaknesses() {
        return factors.stream().filter(f -> !f.met()).toList();
    }
}
