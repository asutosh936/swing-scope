package com.swingscope.service.levels;

import com.swingscope.domain.levels.SwingPoint;
import com.swingscope.domain.marketdata.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds pivots — the local turning points support and resistance are built from.
 *
 * <p>Fractal definition: with {@code strength = n}, a swing low is a bar whose low is strictly below
 * the lows of the {@code n} bars before <em>and</em> the {@code n} bars after it. A swing high is the
 * mirror. Larger {@code n} means fewer, more significant pivots.
 *
 * <h2>Two properties a backtest depends on</h2>
 * <ol>
 *   <li><strong>The last {@code n} bars can never produce a pivot</strong>, because confirmation
 *       needs {@code n} bars to its right that do not exist yet. This is not a bug to be worked
 *       around — a pivot you can only see in hindsight is not one you could have traded. Callers
 *       get {@link #unconfirmedTailBars} so they can say so out loud.</li>
 *   <li>Detection reads only the list it is handed. Passing a sublist ending at the entry bar is
 *       therefore sufficient to guarantee no lookahead.</li>
 * </ol>
 *
 * <p>Strict inequality on both sides means a flat double-bottom at exactly the same low registers
 * as no pivot rather than two. That is deliberate: an ambiguous turn is not a turn.
 */
@Component
public class SwingPointDetector {

    private static final Logger log = LoggerFactory.getLogger(SwingPointDetector.class);

    /** Default confirmation width. 3 bars either side filters intraday noise on a daily chart. */
    public static final int DEFAULT_STRENGTH = 3;

    public List<SwingPoint> detect(List<Candle> bars) {
        return detect(bars, DEFAULT_STRENGTH);
    }

    /**
     * @param bars     daily candles in chronological order, oldest first
     * @param strength how many bars either side must be higher (for a low) or lower (for a high)
     * @return pivots in chronological order; empty when the series is too short to confirm any
     */
    public List<SwingPoint> detect(List<Candle> bars, int strength) {
        if (strength < 1) {
            throw new IllegalArgumentException("swing strength must be at least 1, got " + strength);
        }
        if (bars == null || bars.size() < minimumBars(strength)) {
            log.debug("Not enough bars for swing detection at strength {}: have {}, need {}",
                    strength, bars == null ? 0 : bars.size(), minimumBars(strength));
            return List.of();
        }

        List<SwingPoint> pivots = new ArrayList<>();
        for (int i = strength; i < bars.size() - strength; i++) {
            Candle bar = bars.get(i);
            if (isSwingLow(bars, i, strength)) {
                pivots.add(new SwingPoint(i, bar.date(), bar.low(), SwingPoint.Type.LOW));
            }
            // A bar can be both in principle; in practice the strict comparisons make it vanishingly
            // rare, and recording both is more honest than picking one.
            if (isSwingHigh(bars, i, strength)) {
                pivots.add(new SwingPoint(i, bar.date(), bar.high(), SwingPoint.Type.HIGH));
            }
        }

        log.debug("Swing detection over {} bars at strength {}: {} pivot(s)",
                bars.size(), strength, pivots.size());
        return List.copyOf(pivots);
    }

    public List<SwingPoint> lows(List<Candle> bars, int strength) {
        return detect(bars, strength).stream().filter(SwingPoint::isLow).toList();
    }

    public List<SwingPoint> highs(List<Candle> bars, int strength) {
        return detect(bars, strength).stream().filter(SwingPoint::isHigh).toList();
    }

    /** Shortest series that can confirm a single pivot: n before, the pivot, n after. */
    public static int minimumBars(int strength) {
        return 2 * strength + 1;
    }

    /**
     * How many bars at the end of the series cannot yet be judged. A caller showing "most recent
     * support" should disclose that anything inside this window is still unconfirmed.
     */
    public static int unconfirmedTailBars(int strength) {
        return strength;
    }

    private static boolean isSwingLow(List<Candle> bars, int index, int strength) {
        java.math.BigDecimal low = bars.get(index).low();
        for (int offset = 1; offset <= strength; offset++) {
            if (low.compareTo(bars.get(index - offset).low()) >= 0
                    || low.compareTo(bars.get(index + offset).low()) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSwingHigh(List<Candle> bars, int index, int strength) {
        java.math.BigDecimal high = bars.get(index).high();
        for (int offset = 1; offset <= strength; offset++) {
            if (high.compareTo(bars.get(index - offset).high()) <= 0
                    || high.compareTo(bars.get(index + offset).high()) <= 0) {
                return false;
            }
        }
        return true;
    }
}
