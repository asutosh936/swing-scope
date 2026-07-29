package com.swingscope.service.marketdata;

import com.swingscope.config.MarketDataProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.function.Supplier;

/**
 * Shared plumbing for the REST providers: one place that logs every outbound call with its timing,
 * retries a 429 with exponential backoff, and turns transport failures into
 * {@link MarketDataException}.
 */
public abstract class AbstractRestProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(AbstractRestProvider.class);

    protected final RestClient http;
    protected final MarketDataProperties.Provider config;

    protected AbstractRestProvider(RestClient.Builder builder, MarketDataProperties.Provider config) {
        this.config = config;
        this.http = builder.baseUrl(config.baseUrl() == null ? "" : config.baseUrl()).build();
        if (config.isUsable()) {
            log.info("Market data provider '{}' enabled at {} (retries={}, backoff={})",
                    name(), config.baseUrl(), config.retries(), config.retryBackoff());
        } else {
            log.warn("Market data provider '{}' is NOT usable — enabled={}, apiKey={}, baseUrl={}. "
                            + "Calls to it will fail fast.",
                    name(), config.enabled(),
                    config.apiKey() == null || config.apiKey().isBlank() ? "missing" : "present",
                    config.baseUrl());
        }
    }

    @Override
    public boolean isAvailable() {
        return config.isUsable();
    }

    protected void requireAvailable() {
        if (!isAvailable()) {
            throw new ProviderUnavailableException(name(),
                    "%s is not configured — set its API key via the environment".formatted(name()));
        }
    }

    /**
     * Runs one outbound call: logs it, times it, and retries on a rate-limit response.
     *
     * @param what  short description for the log line, e.g. {@code quote AAPL}
     * @param call  performs the HTTP request and returns the parsed body
     */
    protected <T> T execute(String what, Supplier<T> call) {
        requireAvailable();

        int attempt = 0;
        long backoffMillis = config.retryBackoff().toMillis();

        while (true) {
            attempt++;
            long startedAt = System.nanoTime();
            try {
                T result = call.get();
                log.info("[{}] {} OK in {}ms{}", name(), what, millisSince(startedAt),
                        attempt > 1 ? " (attempt " + attempt + ")" : "");
                return result;
            } catch (RateLimitedException e) {
                if (attempt > config.retries()) {
                    log.error("[{}] {} rate-limited and out of retries after {} attempt(s)",
                            name(), what, attempt);
                    throw e;
                }
                log.warn("[{}] {} rate-limited (attempt {}/{}) — backing off {}ms",
                        name(), what, attempt, config.retries() + 1, backoffMillis);
                sleep(backoffMillis);
                backoffMillis *= 2;
            } catch (MarketDataException e) {
                log.warn("[{}] {} failed in {}ms: {}", name(), what, millisSince(startedAt), e.getMessage());
                throw e;
            } catch (RestClientException e) {
                log.error("[{}] {} transport failure in {}ms: {}",
                        name(), what, millisSince(startedAt), e.getMessage());
                throw new MarketDataException(name(), "%s call failed: %s".formatted(what, e.getMessage()), e);
            }
        }
    }

    /** Maps a non-2xx response to the right exception type. Shared by both providers. */
    protected MarketDataException fromStatus(HttpStatusCode status, String what, String symbol) {
        int code = status.value();
        if (code == 429) {
            return new RateLimitedException(name(), "%s: rate limit hit on %s".formatted(name(), what));
        }
        if (code == 404) {
            return new UnknownSymbolException(name(), symbol);
        }
        if (code == 401 || code == 403) {
            return new ProviderUnavailableException(name(),
                    "%s rejected the request for %s with HTTP %d — check the API key, or the "
                            + "endpoint may require a paid plan".formatted(name(), what, code));
        }
        return new MarketDataException(name(), "%s returned HTTP %d for %s".formatted(name(), code, what));
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException(name(), "interrupted while backing off from a rate limit", e);
        }
    }
}
