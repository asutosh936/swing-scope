package com.swingscope.service.levels;

import com.swingscope.domain.marketdata.Candle;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Chooses a stop and a target for one bar.
 *
 * <p>Exists so the backtest can compare different level-choosing strategies through the
 * <em>identical</em> walk-forward loop and the identical pessimistic resolvers. If the baseline ran
 * through different simulation code, a difference in results could be the simulation rather than the
 * strategy, and the comparison would prove nothing.
 *
 * <p>Implementations must read only the bars handed to them — that is what keeps the harness free of
 * lookahead.
 */
@FunctionalInterface
public interface LevelProposer {

    /**
     * @param asOf  bars up to and including the signal bar. Nothing later is visible.
     * @param price the signal bar's close
     * @return the levels, or empty when this strategy declines to propose any
     */
    Optional<ProposedLevels> propose(String symbol, List<Candle> asOf, BigDecimal price);

    /** Short name for reports. */
    default String name() {
        return getClass().getSimpleName();
    }

    record ProposedLevels(BigDecimal stop, BigDecimal target) {
    }
}
