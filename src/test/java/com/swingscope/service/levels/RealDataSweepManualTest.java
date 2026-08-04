package com.swingscope.service.levels;

import com.swingscope.config.MarketDataProperties;
import com.swingscope.domain.backtest.BacktestSettings;
import com.swingscope.domain.marketdata.Candle;
import com.swingscope.service.marketdata.twelvedata.TwelveDataClient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the 6A sweep against <strong>live</strong> Twelve Data history and prints the ranking.
 *
 * <p>{@code @Disabled} by design: it hits the network, costs one provider call per symbol, and takes
 * minutes. It is the measurement vehicle for 6A.8 — re-run it deliberately when you want to revisit
 * the adopted parameters, then record the date, sample size and out-of-sample expectancy in the plan.
 *
 * <p>Enable by removing {@code @Disabled}, or run a single method with
 * {@code mvn test -Dtest=RealDataSweepManualTest#sweepOneSymbol}.
 */
@Disabled("hits the live API; run deliberately for 6A.8")
class RealDataSweepManualTest {

    private static final Pattern KEY = Pattern.compile(
            "twelvedata:.*?api-key:\\s*\\$\\{[A-Z_]+:([^}]*)}", Pattern.DOTALL);

    private static TwelveDataClient client() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        Matcher m = KEY.matcher(yml);
        if (!m.find() || m.group(1).isBlank()) {
            throw new IllegalStateException("no Twelve Data key in application.yml");
        }
        MarketDataProperties.Provider provider = new MarketDataProperties.Provider(
                "https://api.twelvedata.com", m.group(1).trim(), true, 2, Duration.ofMillis(500), 8);
        return new TwelveDataClient(RestClient.builder(),
                new MarketDataProperties(provider, null, null));
    }

    private static ParameterSweep sweep() {
        return new ParameterSweep(new AtrCalculator(), new SwingPointDetector());
    }

    @Test
    void sweepOneSymbol() throws Exception {
        run(List.of("AAPL"), 5000);
    }

    @Test
    void sweepSeveralSymbols() throws Exception {
        run(List.of("AAPL", "MSFT", "KO", "VZ", "CARR"), 5000);
    }

    /** 6A.8 — the wide run the adoption decision rests on. ~20 symbols across sectors. */
    @Test
    void sweepWideUniverse() throws Exception {
        run(List.of("AAPL", "MSFT", "JNJ", "KO", "VZ", "XOM", "JPM", "PG", "WMT", "CAT",
                "MRK", "T", "CVX", "PEP", "HD", "UNH", "IBM", "MMM", "GE", "F"), 5000);
    }

    private static void run(List<String> symbols, int bars) throws Exception {
        TwelveDataClient client = client();
        Map<String, List<Candle>> bySymbol = new LinkedHashMap<>();
        for (String symbol : symbols) {
            List<Candle> candles = client.getDailyCandles(symbol, bars).bars();
            bySymbol.put(symbol, candles);
            System.out.printf("%-6s %d bars  %s .. %s%n", symbol, candles.size(),
                    candles.get(0).date(), candles.get(candles.size() - 1).date());
        }

        long started = System.currentTimeMillis();
        List<ParameterSweep.Result> ranked = sweep().sweepAcross(bySymbol,
                ParameterSweep.Grid.defaults(), BacktestSettings.defaults(), new BigDecimal("0.70"));
        long seconds = (System.currentTimeMillis() - started) / 1000;

        System.out.printf("%n=== ranked by OUT-OF-SAMPLE expectancy (%ds) ===%n", seconds);
        System.out.printf("%-42s %8s %14s %8s %7s %6s%n",
                "set", "mean-R", "matched-base", "beats?", "n(indep)", "adopt");
        for (ParameterSweep.Result r : ranked) {
            System.out.printf("%-42s %8s %14s %8s %7d %6s%n",
                    r.label().length() > 40 ? r.label().substring(0, 40) : r.label(),
                    r.outOfSampleExpectancy(), r.matchedBaselineR(),
                    r.baseline() ? "(base)" : (r.beatsBaseline() ? "yes" : "no"),
                    r.report().outOfSample().nonOverlappingEstimate(),
                    r.adoptable() ? "YES" : "-");
        }
    }
}
