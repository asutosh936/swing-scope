package com.swingscope.service.levels;

import com.swingscope.domain.levels.LevelAnalysis;
import com.swingscope.domain.levels.PriceZone;
import com.swingscope.domain.marketdata.Candle;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * Renders the recent price series with its zones as a self-contained inline SVG (task 6.8).
 *
 * <p>The point is to make a suggestion <em>checkable at a glance</em>. A number with a paragraph of
 * rationale still asks you to take it on trust; seeing the shelf the number came from is how you
 * confirm or reject it in two seconds — which is the closest honest substitute for reading the
 * chart yourself.
 *
 * <p>Server-rendered markup, no charting library and no JavaScript. Every value written into the
 * output is a number this class formats itself, so there is nothing user-supplied to escape.
 */
@Component
public class LevelChartRenderer {

    private static final int WIDTH = 700;
    private static final int HEIGHT = 220;
    private static final int PAD_LEFT = 8;
    private static final int PAD_RIGHT = 58;      // room for the price labels
    private static final int PAD_Y = 12;
    private static final int DEFAULT_BARS = 120;

    public String render(LevelAnalysis analysis, List<Candle> bars) {
        return render(analysis, bars, DEFAULT_BARS);
    }

    public String render(LevelAnalysis analysis, List<Candle> bars, int barsToShow) {
        if (bars == null || bars.size() < 2) {
            return "<!-- no chart: not enough bars -->";
        }
        List<Candle> window = bars.size() <= barsToShow
                ? bars
                : bars.subList(bars.size() - barsToShow, bars.size());

        // Vertical range covers the price action AND every drawn level, so nothing lands off-canvas.
        double min = window.stream().mapToDouble(c -> c.low().doubleValue()).min().orElse(0);
        double max = window.stream().mapToDouble(c -> c.high().doubleValue()).max().orElse(1);
        min = includeLevel(min, max, analysis)[0];
        max = includeLevel(min, max, analysis)[1];
        if (max - min < 1e-9) {
            max = min + 1;
        }
        double pad = (max - min) * 0.06;
        min -= pad;
        max += pad;

        StringBuilder svg = new StringBuilder(4096);
        svg.append("<svg class=\"level-chart\" viewBox=\"0 0 ").append(WIDTH).append(' ').append(HEIGHT)
                .append("\" xmlns=\"http://www.w3.org/2000/svg\" role=\"img\" ")
                .append("aria-label=\"Recent price with support and resistance zones\">");

        for (PriceZone zone : analysis.supports()) {
            appendZone(svg, zone, min, max, "support");
        }
        for (PriceZone zone : analysis.resistances()) {
            appendZone(svg, zone, min, max, "resistance");
        }

        svg.append("<polyline class=\"chart-line\" points=\"");
        for (int i = 0; i < window.size(); i++) {
            double x = x(i, window.size());
            double y = y(window.get(i).close().doubleValue(), min, max);
            svg.append(fmt(x)).append(',').append(fmt(y)).append(' ');
        }
        svg.append("\"/>");

        if (analysis.stop().isPresent()) {
            appendLevelLine(svg, analysis.stop().value(), min, max, "stop", "stop");
        }
        if (analysis.target().isPresent()) {
            appendLevelLine(svg, analysis.target().value(), min, max, "target", "target");
        }
        if (analysis.price() != null) {
            appendLevelLine(svg, analysis.price(), min, max, "entry", "entry");
        }

        svg.append("</svg>");
        return svg.toString();
    }

    private static double[] includeLevel(double min, double max, LevelAnalysis analysis) {
        double lo = min;
        double hi = max;
        for (BigDecimal value : new BigDecimal[]{
                analysis.stop().isPresent() ? analysis.stop().value() : null,
                analysis.target().isPresent() ? analysis.target().value() : null,
                analysis.price()}) {
            if (value != null) {
                lo = Math.min(lo, value.doubleValue());
                hi = Math.max(hi, value.doubleValue());
            }
        }
        return new double[]{lo, hi};
    }

    private void appendZone(StringBuilder svg, PriceZone zone, double min, double max, String css) {
        double top = y(zone.high().doubleValue(), min, max);
        double bottom = y(zone.low().doubleValue(), min, max);
        double height = Math.max(2, bottom - top);   // always visible, even for a razor-thin zone
        svg.append("<rect class=\"zone zone-").append(css).append("\" x=\"").append(PAD_LEFT)
                .append("\" y=\"").append(fmt(top))
                .append("\" width=\"").append(WIDTH - PAD_LEFT - PAD_RIGHT)
                .append("\" height=\"").append(fmt(height)).append("\"/>");
    }

    private void appendLevelLine(StringBuilder svg, BigDecimal value, double min, double max,
                                 String css, String label) {
        double y = y(value.doubleValue(), min, max);
        svg.append("<line class=\"level level-").append(css).append("\" x1=\"").append(PAD_LEFT)
                .append("\" y1=\"").append(fmt(y))
                .append("\" x2=\"").append(WIDTH - PAD_RIGHT)
                .append("\" y2=\"").append(fmt(y)).append("\"/>");
        svg.append("<text class=\"level-label level-").append(css).append("\" x=\"")
                .append(WIDTH - PAD_RIGHT + 6).append("\" y=\"").append(fmt(y + 3.5)).append("\">")
                .append(label).append(' ')
                .append(value.setScale(2, RoundingMode.HALF_UP).toPlainString())
                .append("</text>");
    }

    private static double x(int index, int count) {
        double usable = WIDTH - PAD_LEFT - PAD_RIGHT;
        return PAD_LEFT + (count == 1 ? usable / 2 : usable * index / (count - 1.0));
    }

    private static double y(double price, double min, double max) {
        double usable = HEIGHT - 2.0 * PAD_Y;
        return PAD_Y + usable * (1 - (price - min) / (max - min));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
