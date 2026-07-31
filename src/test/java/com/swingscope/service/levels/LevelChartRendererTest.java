package com.swingscope.service.levels;

import com.swingscope.domain.levels.LevelAnalysis;
import com.swingscope.domain.levels.LevelSuggestion;
import com.swingscope.domain.levels.PriceZone;
import com.swingscope.domain.marketdata.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LevelChartRendererTest {

    private final LevelChartRenderer renderer = new LevelChartRenderer();

    private static List<Candle> series(int count) {
        List<Candle> bars = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < count; i++) {
            double p = 40 + 5 * Math.sin(i / 6.0);
            bars.add(new Candle(date.plusDays(i), BigDecimal.valueOf(p),
                    BigDecimal.valueOf(p + 1), BigDecimal.valueOf(p - 1),
                    BigDecimal.valueOf(p), 1_000_000L));
        }
        return bars;
    }

    private static LevelAnalysis analysis(LevelSuggestion stop, LevelSuggestion target) {
        PriceZone support = new PriceZone(PriceZone.Type.SUPPORT, BigDecimal.valueOf(35),
                BigDecimal.valueOf(36), BigDecimal.valueOf(35.5), 3, 50, 10,
                BigDecimal.valueOf(3.9), List.of());
        PriceZone resistance = new PriceZone(PriceZone.Type.RESISTANCE, BigDecimal.valueOf(45),
                BigDecimal.valueOf(46), BigDecimal.valueOf(45.5), 2, 60, 20,
                BigDecimal.valueOf(2.9), List.of());
        return new LevelAnalysis("TEST", BigDecimal.valueOf(40), BigDecimal.valueOf(2),
                stop, target, List.of(support), List.of(resistance), 120, 3, List.of());
    }

    private static LevelSuggestion at(String value) {
        return new LevelSuggestion(new BigDecimal(value), "because", LevelSuggestion.Confidence.HIGH, null);
    }

    @Test
    @DisplayName("draws the price line, both zones and all three levels")
    void rendersACompleteChart() {
        String svg = renderer.render(analysis(at("34.50"), at("45.00")), series(120));

        assertThat(svg).startsWith("<svg").endsWith("</svg>");
        assertThat(svg)
                .contains("class=\"chart-line\"")
                .contains("zone-support")
                .contains("zone-resistance")
                .contains("level-stop")
                .contains("level-target")
                .contains("level-entry")
                .contains("stop 34.50")
                .contains("target 45.00");
    }

    @Test
    @DisplayName("a refused level is simply not drawn")
    void omitsRefusedLevels() {
        String svg = renderer.render(analysis(LevelSuggestion.none("no support"), at("45.00")), series(120));

        assertThat(svg).doesNotContain("level-stop");
        assertThat(svg).contains("level-target");
    }

    @Test
    @DisplayName("levels outside the visible price range still land on the canvas")
    void scalesToIncludeLevelsBeyondThePriceRange() {
        // Price oscillates 35-45; a stop at 20 must not be clipped off the bottom.
        String svg = renderer.render(analysis(at("20.00"), at("45.00")), series(120));

        assertThat(svg).contains("stop 20.00");
        for (String y : yValues(svg)) {
            double value = Double.parseDouble(y);
            assertThat(value).isBetween(0.0, 220.0);
        }
    }

    @Test
    void handlesTooFewBarsWithoutBlowingUp() {
        assertThat(renderer.render(analysis(at("34.50"), at("45.00")), List.of()))
                .contains("no chart");
        assertThat(renderer.render(analysis(at("34.50"), at("45.00")), null))
                .contains("no chart");
    }

    @Test
    @DisplayName("a flat series does not divide by zero")
    void handlesAFlatSeries() {
        List<Candle> flat = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 30; i++) {
            flat.add(new Candle(date.plusDays(i), BigDecimal.TEN, BigDecimal.TEN,
                    BigDecimal.TEN, BigDecimal.TEN, 1_000L));
        }
        LevelAnalysis flatAnalysis = new LevelAnalysis("FLAT", BigDecimal.TEN, BigDecimal.ONE,
                LevelSuggestion.none("n/a"), LevelSuggestion.none("n/a"),
                List.of(), List.of(), 30, 3, List.of());

        String svg = renderer.render(flatAnalysis, flat);

        assertThat(svg).contains("<polyline");
        assertThat(svg).doesNotContain("NaN").doesNotContain("Infinity");
    }

    @Test
    void showsOnlyTheMostRecentWindow() {
        String svg = renderer.render(analysis(at("34.50"), at("45.00")), series(400));

        long points = svg.chars().filter(c -> c == ',').count();
        assertThat(points).isLessThanOrEqualTo(120);
    }

    private static List<String> yValues(String svg) {
        List<String> values = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("y1?=\"([0-9.]+)\"").matcher(svg);
        while (m.find()) {
            values.add(m.group(1));
        }
        return values;
    }
}
