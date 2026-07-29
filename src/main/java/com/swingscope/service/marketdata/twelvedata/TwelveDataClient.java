package com.swingscope.service.marketdata.twelvedata;

import com.swingscope.config.MarketDataProperties;
import com.swingscope.domain.marketdata.Candle;
import com.swingscope.domain.marketdata.Candles;
import com.swingscope.domain.marketdata.Quote;
import com.swingscope.domain.marketdata.SymbolMatch;
import com.swingscope.service.marketdata.AbstractRestProvider;
import com.swingscope.service.marketdata.MarketDataException;
import com.swingscope.service.marketdata.RateLimitedException;
import com.swingscope.service.marketdata.UnknownSymbolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Primary provider: quotes, daily candles and symbol search.
 *
 * <p>Candles come from here rather than Finnhub because Finnhub moved {@code /stock/candle} to its
 * premium tier. EMAs are computed in-app from these closes rather than pulled from
 * {@code /ema}, both to match the user's chart settings and to spend fewer of the 800 daily calls.
 */
@Component
public class TwelveDataClient extends AbstractRestProvider {

    private static final Logger log = LoggerFactory.getLogger(TwelveDataClient.class);

    private static final String NAME = "twelvedata";
    private static final Set<Capability> CAPABILITIES =
            Set.of(Capability.QUOTE, Capability.DAILY_CANDLES, Capability.SYMBOL_SEARCH);

    public TwelveDataClient(RestClient.Builder builder, MarketDataProperties properties) {
        super(builder, properties.twelvedata());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Quote getQuote(String symbol) {
        return execute("quote " + symbol, () -> {
            TwelveDataDtos.QuoteResponse body = http.get()
                    .uri(uri -> uri.path("/quote")
                            .queryParam("symbol", symbol)
                            .queryParam("apikey", config.apiKey())
                            .build())
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw fromStatus(response.getStatusCode(), "quote " + symbol, symbol);
                        }
                        return response.bodyTo(TwelveDataDtos.QuoteResponse.class);
                    });

            checkPayloadStatus(body == null ? null : body.status(),
                    body == null ? null : body.code(),
                    body == null ? null : body.message(),
                    "quote " + symbol, symbol);

            if (body == null || body.close() == null) {
                throw new UnknownSymbolException(NAME, symbol);
            }

            Quote quote = new Quote(
                    body.symbol() == null ? symbol : body.symbol(),
                    decimal(body.close()),
                    decimal(body.previousClose()),
                    decimal(body.change()),
                    decimal(body.percentChange()),
                    integer(body.volume()),
                    integer(body.averageVolume()));

            log.debug("Quote {}: price={} change%={} volume={}",
                    quote.symbol(), quote.price(), quote.changePercent(), quote.volume());
            return quote;
        });
    }

    @Override
    public Candles getDailyCandles(String symbol, int outputSize) {
        return execute("candles %s x%d".formatted(symbol, outputSize), () -> {
            TwelveDataDtos.TimeSeriesResponse body = http.get()
                    .uri(uri -> uri.path("/time_series")
                            .queryParam("symbol", symbol)
                            .queryParam("interval", "1day")
                            .queryParam("outputsize", outputSize)
                            .queryParam("apikey", config.apiKey())
                            .build())
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw fromStatus(response.getStatusCode(), "candles " + symbol, symbol);
                        }
                        return response.bodyTo(TwelveDataDtos.TimeSeriesResponse.class);
                    });

            checkPayloadStatus(body == null ? null : body.status(),
                    body == null ? null : body.code(),
                    body == null ? null : body.message(),
                    "candles " + symbol, symbol);

            if (body == null || body.values() == null || body.values().isEmpty()) {
                throw new UnknownSymbolException(NAME, symbol);
            }

            // Twelve Data returns newest-first; the EMA walk needs oldest-first.
            List<Candle> bars = new ArrayList<>(body.values().size());
            for (TwelveDataDtos.TimeSeriesResponse.Value v : body.values()) {
                bars.add(new Candle(date(v.datetime()), decimal(v.open()), decimal(v.high()),
                        decimal(v.low()), decimal(v.close()), integer(v.volume())));
            }
            bars.sort(Comparator.comparing(Candle::date));

            log.debug("Candles {}: {} bars from {} to {}", symbol, bars.size(),
                    bars.get(0).date(), bars.get(bars.size() - 1).date());
            if (bars.size() < outputSize) {
                log.info("Candles {}: asked for {} bars, provider returned {} — EMA200 needs 200",
                        symbol, outputSize, bars.size());
            }
            return new Candles(symbol, bars);
        });
    }

    @Override
    public List<SymbolMatch> search(String query) {
        return execute("search " + query, () -> {
            TwelveDataDtos.SymbolSearchResponse body = http.get()
                    .uri(uri -> uri.path("/symbol_search")
                            .queryParam("symbol", query)
                            .queryParam("apikey", config.apiKey())
                            .build())
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw fromStatus(response.getStatusCode(), "search " + query, query);
                        }
                        return response.bodyTo(TwelveDataDtos.SymbolSearchResponse.class);
                    });

            checkPayloadStatus(body == null ? null : body.status(),
                    body == null ? null : body.code(),
                    body == null ? null : body.message(),
                    "search " + query, query);

            if (body == null || body.data() == null) {
                return List.of();
            }
            List<SymbolMatch> matches = body.data().stream()
                    .map(m -> new SymbolMatch(m.symbol(), m.instrumentName(), m.exchange(),
                            m.instrumentType()))
                    .toList();
            log.debug("Search '{}' returned {} match(es)", query, matches.size());
            return matches;
        });
    }

    /**
     * Twelve Data signals most failures with HTTP 200 and an error body, so the payload has to be
     * inspected even on a "successful" response.
     */
    private void checkPayloadStatus(String status, Integer code, String message, String what, String symbol) {
        if (!"error".equalsIgnoreCase(status)) {
            return;
        }
        int errorCode = code == null ? 0 : code;
        String detail = message == null ? "no detail" : message;
        log.warn("[{}] {} returned an error payload: code={} message={}", NAME, what, errorCode, detail);

        if (errorCode == 429) {
            throw new RateLimitedException(NAME,
                    "%s rate limit reached (800/day, 8/min on the free tier): %s".formatted(NAME, detail));
        }
        if (errorCode == 404 || detail.toLowerCase().contains("not found")
                || detail.toLowerCase().contains("**symbol** not found")) {
            throw new UnknownSymbolException(NAME, symbol);
        }
        if (errorCode == 401 || errorCode == 403) {
            throw new com.swingscope.service.marketdata.ProviderUnavailableException(NAME,
                    "%s rejected the API key (code %d): %s".formatted(NAME, errorCode, detail));
        }
        throw new MarketDataException(NAME, "%s error %d on %s: %s".formatted(NAME, errorCode, what, detail));
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("[{}] could not parse '{}' as a number — treating as absent", NAME, raw);
            return null;
        }
    }

    private static Long integer(String raw) {
        BigDecimal value = decimal(raw);
        return value == null ? null : value.longValue();
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            // Daily bars are "2024-01-05"; intraday would carry a time we don't need.
            return LocalDate.parse(raw.trim().substring(0, Math.min(10, raw.trim().length())));
        } catch (DateTimeParseException | IndexOutOfBoundsException e) {
            throw new MarketDataException(NAME, "unparseable candle date '%s'".formatted(raw), e);
        }
    }
}
