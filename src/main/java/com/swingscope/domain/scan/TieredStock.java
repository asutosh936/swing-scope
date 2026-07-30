package com.swingscope.domain.scan;

import com.swingscope.domain.marketdata.MarketSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * One scanned candidate: the fetched facts, the tier, and a short machine reason.
 *
 * <p>{@code price} doubles as the suggested entry for "plan this trade" — the current price is a
 * fact, not a signal. Stop and target stay blank because no API supplies support and resistance;
 * reading those off the chart is the judgment this tool deliberately keeps human.
 *
 * @param reason plain-language why, e.g. "below 50-EMA" or "earnings in 2 days"
 * @param distanceToEma50Percent how far above (+) or below (−) the 50-EMA price sits, in percent
 */
public record TieredStock(
        String symbol,
        Tier tier,
        String reason,
        BigDecimal price,
        BigDecimal changePercent,
        BigDecimal ema20,
        BigDecimal ema50,
        BigDecimal ema200,
        BigDecimal distanceToEma50Percent,
        Long volume,
        Long averageVolume,
        BigDecimal marketCapMillions,
        LocalDate nextEarningsDate,
        Boolean inUptrend,
        boolean bigMover,
        boolean earningsWithin3Days
) {

    public static TieredStock from(MarketSnapshot snapshot, Tier tier, String reason) {
        return new TieredStock(
                snapshot.symbol(), tier, reason,
                snapshot.price(), round2(snapshot.changePercent()),
                snapshot.ema20(), snapshot.ema50(), snapshot.ema200(),
                percentAbove(snapshot.price(), snapshot.ema50()),
                snapshot.volume(), snapshot.averageVolume(),
                snapshot.marketCap(), snapshot.nextEarningsDate(),
                snapshot.inUptrend(), snapshot.bigMover(), snapshot.earningsWithin3Days());
    }

    /** A ticker whose data could not be fetched — the failure is reported, never guessed around. */
    public static TieredStock unavailable(String symbol, String reason) {
        return new TieredStock(symbol, Tier.UNAVAILABLE, reason,
                null, null, null, null, null, null, null, null, null, null, null, false, false);
    }

    /** Providers return change% at full float precision; two decimals is all a human needs. */
    private static BigDecimal round2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentAbove(BigDecimal price, BigDecimal ema) {
        if (price == null || ema == null || ema.signum() == 0) {
            return null;
        }
        return price.subtract(ema)
                .multiply(new BigDecimal("100"))
                .divide(ema, 2, RoundingMode.HALF_UP);
    }

    /** Market cap in billions, for display. Finnhub reports millions. */
    public BigDecimal marketCapBillions() {
        return marketCapMillions == null ? null
                : marketCapMillions.divide(new BigDecimal("1000"), 2, RoundingMode.HALF_UP);
    }
}
