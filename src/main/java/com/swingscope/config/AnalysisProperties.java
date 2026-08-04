package com.swingscope.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * The account assumed when auto-analysing scan candidates.
 *
 * <p>A batch analysis has to assume an account size and a risk budget, because a share count is
 * meaningless without them. Those assumptions are stated on the results page rather than left
 * implicit — a reader who thinks the numbers were computed for a different account would be
 * misled about every row.
 *
 * @param autoAnalyse turn the whole feature off; tiering still works
 */
@ConfigurationProperties(prefix = "analysis")
public record AnalysisProperties(
        Boolean autoAnalyse,
        BigDecimal accountSize,
        BigDecimal riskAmount
) {

    public AnalysisProperties {
        if (autoAnalyse == null) {
            autoAnalyse = true;
        }
        if (accountSize == null) {
            accountSize = new BigDecimal("500");
        }
        if (riskAmount == null) {
            riskAmount = new BigDecimal("5.00");
        }
    }
}
