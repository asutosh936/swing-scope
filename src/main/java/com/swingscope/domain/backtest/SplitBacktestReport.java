package com.swingscope.domain.backtest;

import java.math.BigDecimal;

/**
 * The same replay, reported twice: on the older bars used for tuning, and on the newer bars held
 * back.
 *
 * <p><strong>Why both numbers must always be shown together.</strong> Tuning parameters until they
 * look good on the data you tuned them on is not measurement, it is curve fitting, and in trading the
 * feedback loop is slow enough that you will not notice for months. A set that wins in-sample and
 * loses out-of-sample has told you something important: the edge was noise.
 *
 * <p>The split is <em>chronological</em> and applied to the resulting trades, not to the input bars.
 * Every entry — in either half — is still computed from the full history preceding it, so the
 * out-of-sample entries are not handicapped by a truncated lookback. Only the reporting is split.
 *
 * @param splitBarIndex first bar index counted as out-of-sample
 */
public record SplitBacktestReport(
        String symbol,
        BacktestReport inSample,
        BacktestReport outOfSample,
        int splitBarIndex,
        BigDecimal inSampleFraction
) {

    /**
     * The headline: how much worse the held-back half performed. Large positive degradation is the
     * signature of overfitting.
     */
    public BigDecimal degradationR() {
        return inSample.expectancyR().subtract(outOfSample.expectancyR());
    }

    /** Out-of-sample expectancy is the only number a parameter set should ever be ranked on. */
    public BigDecimal rankingScore() {
        return outOfSample.expectancyR();
    }

    /** Too few held-back trades to conclude anything, however good the number looks. */
    public boolean isConclusive(int minimumOutOfSampleTrades) {
        return outOfSample.nonOverlappingEstimate() >= minimumOutOfSampleTrades;
    }
}
