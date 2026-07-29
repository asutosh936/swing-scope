package com.swingscope.service.marketdata.finnhub;

import com.swingscope.config.MarketDataProperties;
import com.swingscope.domain.marketdata.CompanyProfile;
import com.swingscope.domain.marketdata.EarningsEvent;
import com.swingscope.domain.marketdata.MarketStatus;
import com.swingscope.domain.marketdata.NewsItem;
import com.swingscope.service.marketdata.AbstractRestProvider;
import com.swingscope.service.marketdata.MarketDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Secondary provider: the endpoints Finnhub still serves on a free key — earnings calendar, market
 * status and company profile (for market cap).
 *
 * <p>Never used for candles or EMAs. {@code /stock/candle} is premium-only now and answers a free
 * key with HTTP 403, which {@link AbstractRestProvider#fromStatus} surfaces as a clear
 * "requires a paid plan" message rather than a generic failure.
 */
@Component
public class FinnhubClient extends AbstractRestProvider {

    private static final Logger log = LoggerFactory.getLogger(FinnhubClient.class);

    private static final String NAME = "finnhub";
    private static final Set<Capability> CAPABILITIES = Set.of(
            Capability.EARNINGS, Capability.MARKET_STATUS, Capability.COMPANY_PROFILE,
            Capability.COMPANY_NEWS);

    public FinnhubClient(RestClient.Builder builder, MarketDataProperties properties) {
        super(builder, properties.finnhub());
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
    public List<EarningsEvent> getEarnings(String symbol, LocalDate from, LocalDate to) {
        return execute("earnings %s %s..%s".formatted(symbol, from, to), () -> {
            FinnhubDtos.EarningsCalendarResponse body = http.get()
                    .uri(uri -> uri.path("/calendar/earnings")
                            .queryParam("symbol", symbol)
                            .queryParam("from", from)
                            .queryParam("to", to)
                            .queryParam("token", config.apiKey())
                            .build())
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw fromStatus(response.getStatusCode(), "earnings " + symbol, symbol);
                        }
                        return response.bodyTo(FinnhubDtos.EarningsCalendarResponse.class);
                    });

            if (body == null || body.earningsCalendar() == null || body.earningsCalendar().isEmpty()) {
                log.debug("No earnings scheduled for {} between {} and {}", symbol, from, to);
                return List.<EarningsEvent>of();
            }

            List<EarningsEvent> events = body.earningsCalendar().stream()
                    .map(e -> new EarningsEvent(
                            e.symbol() == null ? symbol : e.symbol(),
                            date(e.date()), e.hour(), e.quarter(), e.year()))
                    .filter(e -> e.date() != null)
                    .sorted(Comparator.comparing(EarningsEvent::date))
                    .toList();

            log.debug("Earnings {}: {} event(s), next {}", symbol, events.size(),
                    events.isEmpty() ? "none" : events.get(0).date());
            return events;
        });
    }

    @Override
    public MarketStatus getMarketStatus() {
        return execute("market-status US", () -> {
            FinnhubDtos.MarketStatusResponse body = http.get()
                    .uri(uri -> uri.path("/stock/market-status")
                            .queryParam("exchange", "US")
                            .queryParam("token", config.apiKey())
                            .build())
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw fromStatus(response.getStatusCode(), "market-status", "US");
                        }
                        return response.bodyTo(FinnhubDtos.MarketStatusResponse.class);
                    });

            if (body == null) {
                throw new MarketDataException(NAME, "empty market-status response");
            }
            MarketStatus status = new MarketStatus(
                    body.exchange() == null ? "US" : body.exchange(),
                    Boolean.TRUE.equals(body.isOpen()),
                    body.session(),
                    body.holiday());
            log.debug("Market status: open={} session={} holiday={}",
                    status.open(), status.session(), status.holiday());
            return status;
        });
    }

    @Override
    public CompanyProfile getCompanyProfile(String symbol) {
        return execute("profile " + symbol, () -> {
            FinnhubDtos.CompanyProfileResponse body = http.get()
                    .uri(uri -> uri.path("/stock/profile2")
                            .queryParam("symbol", symbol)
                            .queryParam("token", config.apiKey())
                            .build())
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw fromStatus(response.getStatusCode(), "profile " + symbol, symbol);
                        }
                        return response.bodyTo(FinnhubDtos.CompanyProfileResponse.class);
                    });

            // Finnhub answers an unknown ticker with HTTP 200 and an empty object.
            if (body == null || body.ticker() == null) {
                throw new com.swingscope.service.marketdata.UnknownSymbolException(NAME, symbol);
            }

            CompanyProfile profile = new CompanyProfile(body.ticker(), body.name(), body.exchange(),
                    body.marketCapitalization(), body.finnhubIndustry());
            log.debug("Profile {}: marketCap={}M industry={}",
                    profile.symbol(), profile.marketCap(), profile.industry());
            return profile;
        });
    }

    @Override
    public List<NewsItem> getCompanyNews(String symbol, LocalDate from, LocalDate to) {
        return execute("news %s %s..%s".formatted(symbol, from, to), () -> {
            FinnhubDtos.NewsResponse[] body = http.get()
                    .uri(uri -> uri.path("/company-news")
                            .queryParam("symbol", symbol)
                            .queryParam("from", from)
                            .queryParam("to", to)
                            .queryParam("token", config.apiKey())
                            .build())
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw fromStatus(response.getStatusCode(), "news " + symbol, symbol);
                        }
                        return response.bodyTo(FinnhubDtos.NewsResponse[].class);
                    });

            if (body == null || body.length == 0) {
                log.debug("No news for {} between {} and {}", symbol, from, to);
                return List.<NewsItem>of();
            }

            List<NewsItem> items = java.util.Arrays.stream(body)
                    .map(n -> new NewsItem(
                            n.related() == null || n.related().isBlank() ? symbol : n.related(),
                            n.headline(), n.summary(), n.source(), n.url(), n.category(),
                            n.datetime() == null ? null : Instant.ofEpochSecond(n.datetime())))
                    // Newest first: the most recent story is the one that explains today's move.
                    .sorted(Comparator.comparing(NewsItem::publishedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();

            log.debug("News {}: {} story(ies), latest {}", symbol, items.size(),
                    items.isEmpty() ? "none" : items.get(0).publishedAt());
            return items;
        });
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            log.warn("[{}] unparseable earnings date '{}' — skipping that entry", NAME, raw);
            return null;
        }
    }
}
