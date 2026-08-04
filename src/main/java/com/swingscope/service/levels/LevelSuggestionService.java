package com.swingscope.service.levels;

import com.swingscope.config.LevelProperties;
import com.swingscope.domain.levels.LevelAnalysis;
import com.swingscope.domain.levels.LevelSuggestion;
import com.swingscope.domain.levels.PriceZone;
import com.swingscope.domain.marketdata.Candle;
import com.swingscope.domain.marketdata.Candles;
import com.swingscope.service.marketdata.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Proposes a stop and a target from price structure — or explains why it won't.
 *
 * <h2>What it proposes</h2>
 * <ul>
 *   <li><strong>Stop</strong> = nearest support zone below, minus {@code stopBuffer × ATR}. Below
 *       the shelf rather than at it, so ordinary noise around the level doesn't take you out.</li>
 *   <li><strong>Target</strong> = the <em>near edge</em> of the nearest resistance above, not its
 *       centre. Matches the management rule already shown on a PASS: take profit <em>into</em>
 *       resistance rather than hoping to trade through it.</li>
 * </ul>
 *
 * <h2>What it refuses</h2>
 * Structure-only by design. When there is no clean shelf, it returns a refusal rather than falling
 * back to an ATR-derived number — an arbitrary distance wearing a formula is harder to argue with
 * than a blank field, and therefore more dangerous.
 *
 * <p>Every number here is arithmetic over past price. None of it is a recommendation to trade, and
 * a suggestion is only ever a starting point for the human's own reading of the chart.
 */
@Service
public class LevelSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(LevelSuggestionService.class);

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final MarketDataService marketData;
    private final PriceLevelService levels;
    private final AtrCalculator atrCalculator;
    private final LevelProperties properties;

    public LevelSuggestionService(MarketDataService marketData, PriceLevelService levels,
                                  AtrCalculator atrCalculator, LevelProperties properties) {
        this.marketData = marketData;
        this.levels = levels;
        this.atrCalculator = atrCalculator;
        this.properties = properties;
    }

    /** Fetches candles (cached) and analyses them. Entry is assumed to be the latest close. */
    public LevelAnalysis suggest(String rawSymbol) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase();
        Candles candles = marketData.getDailyCandles(symbol, properties.lookbackBars());
        if (candles == null || candles.isEmpty()) {
            log.info("{}: no candles available — cannot propose levels", symbol);
            return analyse(symbol, List.of(), null);
        }
        Candle latest = candles.latest();
        BigDecimal price = latest == null ? null : latest.close();
        return analyse(symbol, candles.bars(), price);
    }

    /**
     * The pure half — no I/O, so a backtest can call it with a historical sublist.
     *
     * @param bars  daily candles, oldest first. Only these are read: pass bars up to and including
     *              the entry bar and lookahead is impossible.
     * @param price the entry price the levels are measured against
     */
    public LevelAnalysis analyse(String symbol, List<Candle> bars, BigDecimal price) {
        List<String> warnings = new ArrayList<>();
        int barCount = bars == null ? 0 : bars.size();
        int tail = SwingPointDetector.unconfirmedTailBars(properties.pivotStrength());

        // ---- guard: enough history to say anything at all (6.5)
        if (barCount < properties.minBarsForSuggestion()) {
            String why = "only %d bars of history — need %d before proposing a level"
                    .formatted(barCount, properties.minBarsForSuggestion());
            log.info("{}: no suggestions — {}", symbol, why);
            return new LevelAnalysis(symbol, price, null,
                    LevelSuggestion.none(why), LevelSuggestion.none(why),
                    List.of(), List.of(), barCount, tail, List.of(why));
        }
        if (price == null || price.signum() <= 0) {
            String why = "no usable current price";
            return new LevelAnalysis(symbol, price, null,
                    LevelSuggestion.none(why), LevelSuggestion.none(why),
                    List.of(), List.of(), barCount, tail, List.of(why));
        }

        BigDecimal atr = atrCalculator.atr(levels.lookbackWindow(bars), properties.atrPeriod());
        List<PriceZone> supports = levels.supportsBelow(bars, price);
        List<PriceZone> resistances = levels.resistancesAbove(bars, price);

        if (tail > 0) {
            warnings.add("the newest %d bar(s) cannot form a pivot yet — a level inside that window "
                    .formatted(tail) + "would only be visible in hindsight");
        }

        LevelSuggestion stop = proposeStop(symbol, supports, price, atr);
        LevelSuggestion target = proposeTarget(symbol, resistances, price);

        // 6A.8: structure is preferred where it exists, but the wide backtest found it no better
        // than a volatility rule on a matched comparison — and wildly variable by symbol. So rather
        // than leaving the field blank, fall back, and mark it clearly as a fallback.
        if (properties.fallbackToAtr() && atr != null && atr.signum() > 0) {
            if (!stop.isPresent()) {
                stop = atrFallbackStop(symbol, price, atr, stop.rationale());
            }
            if (!target.isPresent() && stop.isPresent()) {
                target = atrFallbackTarget(price, stop.value(), target.rationale());
            }
        }

        LevelAnalysis analysis = new LevelAnalysis(symbol, price, atr, stop, target,
                supports, resistances, barCount, tail, warnings);

        log.info("Levels for {} at {}: stop={} target={} impliedRatio={} ({} support, {} resistance zones)",
                symbol, price,
                stop.isPresent() ? stop.value() : "none",
                target.isPresent() ? target.value() : "none",
                analysis.impliedRatio(), supports.size(), resistances.size());
        return analysis;
    }

    // ------------------------------------------------------------------------------------ stop

    private LevelSuggestion proposeStop(String symbol, List<PriceZone> supports,
                                        BigDecimal price, BigDecimal atr) {
        if (supports.isEmpty()) {
            return LevelSuggestion.none(
                    "no support zone with %d+ touches below the current price — set the stop yourself"
                            .formatted(properties.minTouches()));
        }
        if (atr == null || atr.signum() <= 0) {
            return LevelSuggestion.none("no usable ATR, so the stop buffer cannot be sized");
        }

        PriceZone zone = supports.get(0);
        BigDecimal buffer = atr.multiply(properties.stopBufferAtrMultiple());
        BigDecimal stop = zone.low().subtract(buffer).setScale(2, RoundingMode.HALF_UP);

        // ---- guard: a stop at or below zero is nonsense (6.5)
        if (stop.signum() <= 0) {
            return LevelSuggestion.none("the computed stop falls at or below zero");
        }
        // ---- guard: a stop that far away cannot be sized sensibly (6.5)
        BigDecimal stopPercent = price.subtract(stop)
                .multiply(ONE_HUNDRED).divide(price, 2, RoundingMode.HALF_UP);
        if (stopPercent.compareTo(properties.maxStopPercent()) > 0) {
            return LevelSuggestion.none(
                    "nearest support is %s%% away — wider than the %s%% limit, so this needs your own read"
                            .formatted(stopPercent, properties.maxStopPercent()));
        }

        String rationale = ("support at %s–%s, %d touches, last tested %d bars ago; "
                + "stop placed %s below it (%s × ATR %s) so noise at the level doesn't trigger it")
                .formatted(zone.low(), zone.high(), zone.touches(), zone.barsSinceLastTouch(),
                        buffer.setScale(2, RoundingMode.HALF_UP),
                        properties.stopBufferAtrMultiple(), atr.setScale(2, RoundingMode.HALF_UP));

        log.debug("{}: stop {} from support zone {}–{} ({} touches)",
                symbol, stop, zone.low(), zone.high(), zone.touches());
        return new LevelSuggestion(stop, rationale, confidenceOf(zone), zone,
                LevelSuggestion.Source.STRUCTURE);
    }

    // ---------------------------------------------------------------------------------- target

    private LevelSuggestion proposeTarget(String symbol, List<PriceZone> resistances, BigDecimal price) {
        if (resistances.isEmpty()) {
            return LevelSuggestion.none(
                    "no resistance zone with %d+ touches above the current price — nothing overhead "
                            .formatted(properties.minTouches())
                            + "to aim at, so set the target yourself");
        }

        PriceZone zone = resistances.get(0);
        // The near edge, not the centre: take profit into resistance rather than through it.
        BigDecimal target = zone.low().setScale(2, RoundingMode.HALF_UP);

        if (target.compareTo(price) <= 0) {
            return LevelSuggestion.none("nearest resistance already overlaps the current price");
        }

        String rationale = ("resistance at %s–%s, %d touches, last tested %d bars ago; "
                + "target set at its near edge — take profit into resistance, not through it")
                .formatted(zone.low(), zone.high(), zone.touches(), zone.barsSinceLastTouch());

        log.debug("{}: target {} from resistance zone {}–{} ({} touches)",
                symbol, target, zone.low(), zone.high(), zone.touches());
        return new LevelSuggestion(target, rationale, confidenceOf(zone), zone,
                LevelSuggestion.Source.STRUCTURE);
    }

    /**
     * A stop derived from volatility alone, used when no clean shelf exists. Always LOW confidence
     * and always labelled — it ignores the chart entirely, which is exactly why it needs your eyes.
     */
    private LevelSuggestion atrFallbackStop(String symbol, BigDecimal price, BigDecimal atr,
                                            String whyStructureFailed) {
        BigDecimal stop = price.subtract(atr.multiply(properties.fallbackStopAtrMultiple()))
                .setScale(2, RoundingMode.HALF_UP);
        if (stop.signum() <= 0) {
            return LevelSuggestion.none(whyStructureFailed);
        }
        BigDecimal stopPercent = price.subtract(stop)
                .multiply(ONE_HUNDRED).divide(price, 2, RoundingMode.HALF_UP);
        if (stopPercent.compareTo(properties.maxStopPercent()) > 0) {
            return LevelSuggestion.none(whyStructureFailed
                    + "; the volatility fallback would sit %s%% away, also beyond the %s%% limit"
                    .formatted(stopPercent, properties.maxStopPercent()));
        }

        log.debug("{}: no structural support — falling back to {} × ATR",
                symbol, properties.fallbackStopAtrMultiple());
        return LevelSuggestion.fallback(stop,
                "%s. FALLBACK: %s × ATR below entry (%s), which ignores the chart entirely — "
                        .formatted(whyStructureFailed, properties.fallbackStopAtrMultiple(),
                                atr.setScale(2, RoundingMode.HALF_UP))
                        + "check it against support you can see before accepting it");
    }

    /** A target giving the minimum acceptable reward for whatever risk the stop implies. */
    private LevelSuggestion atrFallbackTarget(BigDecimal price, BigDecimal stop,
                                              String whyStructureFailed) {
        BigDecimal risk = price.subtract(stop);
        if (risk.signum() <= 0) {
            return LevelSuggestion.none(whyStructureFailed);
        }
        BigDecimal target = price.add(risk.multiply(properties.fallbackRewardMultiple()))
                .setScale(2, RoundingMode.HALF_UP);
        return LevelSuggestion.fallback(target,
                "%s. FALLBACK: %s\u00d7 the risk distance, not a level anyone has traded at — "
                        .formatted(whyStructureFailed, properties.fallbackRewardMultiple())
                        + "check it against resistance you can see");
    }

    /** Touches and recency, nothing cleverer — and never above MEDIUM on a stale level. */
    static LevelSuggestion.Confidence confidenceOf(PriceZone zone) {
        boolean recent = zone.barsSinceLastTouch() <= 60;
        if (zone.touches() >= 3 && recent) {
            return LevelSuggestion.Confidence.HIGH;
        }
        if (zone.touches() >= 3 || recent) {
            return LevelSuggestion.Confidence.MEDIUM;
        }
        return LevelSuggestion.Confidence.LOW;
    }
}
