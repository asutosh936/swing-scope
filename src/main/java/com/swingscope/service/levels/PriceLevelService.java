package com.swingscope.service.levels;

import com.swingscope.config.LevelProperties;
import com.swingscope.domain.levels.PriceZone;
import com.swingscope.domain.levels.SwingPoint;
import com.swingscope.domain.marketdata.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns raw pivots into support and resistance <em>zones</em>.
 *
 * <p>Three reversals at 38.40, 38.55 and 38.31 are one shelf, not three lines, so pivots within
 * {@code zoneTolerance × ATR} of each other collapse together. Using ATR rather than a fixed cent
 * amount is what lets the same code work on a $15 stock and a $970 one.
 *
 * <p>Zones are scored, not ranked by opinion: {@code strength = touches + recencyBonus}, where the
 * bonus decays from 1 to 0 across the lookback window. A score is a comparable number, not a
 * probability and not a forecast.
 */
@Service
public class PriceLevelService {

    private static final Logger log = LoggerFactory.getLogger(PriceLevelService.class);

    private final SwingPointDetector detector;
    private final AtrCalculator atrCalculator;
    private final LevelProperties properties;

    public PriceLevelService(SwingPointDetector detector, AtrCalculator atrCalculator,
                             LevelProperties properties) {
        this.detector = detector;
        this.atrCalculator = atrCalculator;
        this.properties = properties;
        log.info("PriceLevelService: pivotStrength={} atrPeriod={} zoneTolerance={}×ATR minTouches={}",
                properties.pivotStrength(), properties.atrPeriod(),
                properties.zoneToleranceAtrMultiple(), properties.minTouches());
    }

    /**
     * Support zones below {@code price}, nearest first.
     *
     * @param bars daily candles, oldest first. Only the bars supplied are read, so a caller may
     *             pass a sublist ending at the entry bar to guarantee no lookahead.
     */
    public List<PriceZone> supportsBelow(List<Candle> bars, BigDecimal price) {
        List<PriceZone> zones = zones(bars, PriceZone.Type.SUPPORT);
        return zones.stream()
                .filter(z -> z.center().compareTo(price) < 0)
                // Nearest below first: the level price would reach soonest.
                .sorted(Comparator.comparing(PriceZone::center, Comparator.reverseOrder()))
                .toList();
    }

    /** Resistance zones above {@code price}, nearest first. */
    public List<PriceZone> resistancesAbove(List<Candle> bars, BigDecimal price) {
        List<PriceZone> zones = zones(bars, PriceZone.Type.RESISTANCE);
        return zones.stream()
                .filter(z -> z.center().compareTo(price) > 0)
                .sorted(Comparator.comparing(PriceZone::center))
                .toList();
    }

    /** All zones of one type found in the series, unfiltered by current price. */
    public List<PriceZone> zones(List<Candle> bars, PriceZone.Type type) {
        if (bars == null || bars.isEmpty()) {
            return List.of();
        }

        List<Candle> window = lookbackWindow(bars);
        BigDecimal atr = atrCalculator.atr(window, properties.atrPeriod());
        if (atr == null || atr.signum() <= 0) {
            log.debug("No usable ATR over {} bars — cannot size zone tolerance", window.size());
            return List.of();
        }

        List<SwingPoint> pivots = type == PriceZone.Type.SUPPORT
                ? detector.lows(window, properties.pivotStrength())
                : detector.highs(window, properties.pivotStrength());
        if (pivots.isEmpty()) {
            return List.of();
        }

        BigDecimal tolerance = atr.multiply(properties.zoneToleranceAtrMultiple());
        List<List<SwingPoint>> clusters = cluster(pivots, tolerance);

        int newestBarIndex = window.size() - 1;
        List<PriceZone> zones = new ArrayList<>();
        for (List<SwingPoint> cluster : clusters) {
            if (cluster.size() < properties.minTouches()) {
                continue;
            }
            zones.add(toZone(cluster, type, newestBarIndex));
        }

        log.debug("{} {} zone(s) from {} pivot(s), tolerance {} ({}×ATR {})",
                zones.size(), type, pivots.size(), tolerance,
                properties.zoneToleranceAtrMultiple(), atr);
        return List.copyOf(zones);
    }

    /** The most recent {@code lookbackBars}; older structure has gone stale. */
    List<Candle> lookbackWindow(List<Candle> bars) {
        int lookback = properties.lookbackBars();
        return bars.size() <= lookback ? bars : bars.subList(bars.size() - lookback, bars.size());
    }

    /**
     * Groups pivots whose prices sit within {@code tolerance} of the running cluster centre.
     *
     * <p>Sorted by price then swept once — a pivot joins the open cluster while it stays within
     * tolerance of that cluster's mean, otherwise it starts a new one.
     */
    private static List<List<SwingPoint>> cluster(List<SwingPoint> pivots, BigDecimal tolerance) {
        List<SwingPoint> byPrice = new ArrayList<>(pivots);
        byPrice.sort(Comparator.comparing(SwingPoint::price));

        List<List<SwingPoint>> clusters = new ArrayList<>();
        List<SwingPoint> current = new ArrayList<>();
        BigDecimal runningSum = BigDecimal.ZERO;

        for (SwingPoint pivot : byPrice) {
            if (current.isEmpty()) {
                current.add(pivot);
                runningSum = pivot.price();
                continue;
            }
            BigDecimal centre = runningSum.divide(BigDecimal.valueOf(current.size()), 6, RoundingMode.HALF_UP);
            if (pivot.price().subtract(centre).abs().compareTo(tolerance) <= 0) {
                current.add(pivot);
                runningSum = runningSum.add(pivot.price());
            } else {
                clusters.add(List.copyOf(current));
                current = new ArrayList<>(List.of(pivot));
                runningSum = pivot.price();
            }
        }
        if (!current.isEmpty()) {
            clusters.add(List.copyOf(current));
        }
        return clusters;
    }

    private static PriceZone toZone(List<SwingPoint> cluster, PriceZone.Type type, int newestBarIndex) {
        BigDecimal low = cluster.stream().map(SwingPoint::price).min(BigDecimal::compareTo).orElseThrow();
        BigDecimal high = cluster.stream().map(SwingPoint::price).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal sum = cluster.stream().map(SwingPoint::price).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal center = sum.divide(BigDecimal.valueOf(cluster.size()), 2, RoundingMode.HALF_UP);

        int lastTouchIndex = cluster.stream().mapToInt(SwingPoint::barIndex).max().orElse(0);
        int barsSince = newestBarIndex - lastTouchIndex;

        List<SwingPoint> chronological = new ArrayList<>(cluster);
        chronological.sort(Comparator.comparingInt(SwingPoint::barIndex));

        return new PriceZone(type, low, high, center, cluster.size(), lastTouchIndex, barsSince,
                strength(cluster.size(), barsSince, newestBarIndex + 1), chronological);
    }

    /**
     * {@code touches + recencyBonus}, where the bonus falls linearly from 1 (tested today) to 0
     * (tested at the far edge of the window). Deliberately simple: a score anyone can recompute by
     * hand is one they can argue with.
     */
    static BigDecimal strength(int touches, int barsSinceLastTouch, int windowSize) {
        BigDecimal recencyBonus = BigDecimal.ZERO;
        if (windowSize > 0 && barsSinceLastTouch < windowSize) {
            recencyBonus = BigDecimal.ONE.subtract(
                    BigDecimal.valueOf(barsSinceLastTouch)
                            .divide(BigDecimal.valueOf(windowSize), 4, RoundingMode.HALF_UP));
        }
        return BigDecimal.valueOf(touches).add(recencyBonus).setScale(2, RoundingMode.HALF_UP);
    }
}
