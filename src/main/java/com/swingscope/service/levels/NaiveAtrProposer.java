package com.swingscope.service.levels;

import com.swingscope.domain.marketdata.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * The baseline the structure-based levels must beat: {@code stop = entry − 2×ATR}, target set to
 * give exactly the minimum acceptable reward:risk.
 *
 * <p>It uses no price structure at all — no pivots, no zones, no clustering. That is the point. If a
 * detector built from swing highs and lows cannot out-perform two lines drawn from volatility alone,
 * then the structure is decoration and the honest move is to ship the baseline, or ship nothing.
 *
 * <p>Per the Phase 6A success criteria, a parameter set is adopted only if it beats this
 * out-of-sample.
 */
/*
 * Deliberately NOT a Spring bean: it is a strategy the sweep constructs per run, with a
 * configurable ATR period. Component-scanning it gave Spring two constructors and no way to choose.
 */
public class NaiveAtrProposer implements LevelProposer {

    private static final BigDecimal STOP_ATR_MULTIPLE = new BigDecimal("2");
    private static final BigDecimal REWARD_MULTIPLE = new BigDecimal("2");

    private final AtrCalculator atrCalculator;
    private final int atrPeriod;

    public NaiveAtrProposer(AtrCalculator atrCalculator) {
        this(atrCalculator, AtrCalculator.DEFAULT_PERIOD);
    }

    public NaiveAtrProposer(AtrCalculator atrCalculator, int atrPeriod) {
        this.atrCalculator = atrCalculator;
        this.atrPeriod = atrPeriod;
    }

    @Override
    public String name() {
        return "naive-atr";
    }

    @Override
    public Optional<ProposedLevels> propose(String symbol, List<Candle> asOf, BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal atr = atrCalculator.atr(asOf, atrPeriod);
        if (atr == null || atr.signum() <= 0) {
            return Optional.empty();
        }

        BigDecimal stop = price.subtract(atr.multiply(STOP_ATR_MULTIPLE))
                .setScale(2, RoundingMode.HALF_UP);
        if (stop.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal risk = price.subtract(stop);
        BigDecimal target = price.add(risk.multiply(REWARD_MULTIPLE)).setScale(2, RoundingMode.HALF_UP);

        return Optional.of(new ProposedLevels(stop, target));
    }
}
