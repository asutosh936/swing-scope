package com.swingscope.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI document for the JSON surface.
 *
 * <p>Only {@code /api/**} is described (see {@code springdoc.paths-to-match}). The other routes are
 * Thymeleaf form handlers that return HTML — documenting them as an API would misrepresent them.
 */
@Configuration
public class OpenApiConfig {

    public static final String TAG_CALCULATOR = "Calculator";
    public static final String TAG_SCAN = "Scan & watchlist";
    public static final String TAG_JOURNAL = "Journal";
    public static final String TAG_MARKET_DATA = "Market data";
    public static final String TAG_BACKTEST = "Backtest";

    @Bean
    public OpenAPI swingScopeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("swing-scope API")
                        .version("0.0.1")
                        .description("""
                                JSON surface of a swing-trade **decision-support** tool.

                                ## ⚠️ Educational tool. Paper trading only. Not financial advice.
                                This API does not place orders, does not connect to a broker, and does \
                                not generate buy or sell signals. It returns arithmetic and rule \
                                outcomes. Every decision, and every consequence of it, is the caller's.

                                ## What this API is, and is not
                                The endpoints here are **read and compute only**. Every write — \
                                planning a trade, recording a fill, closing a position, editing the \
                                watchlist — happens through the web UI, so there is a single write \
                                path rather than two implementations to keep in step. The one \
                                exception is `POST /api/watchlist/{id}/note`, which has no UI \
                                equivalent yet.

                                Two endpoints are POST but change nothing: `/api/analyze` and \
                                `/api/scan` take a request body to compute a result.

                                ## Conventions
                                * All money is decimal, never floating point. Risk is expressed in \
                                **dollars**, not as a percentage of the account.
                                * A setup that fails the trading rules returns **200** with \
                                `pass: false` and a human-readable `reason`. Only structurally \
                                invalid input is a 400.
                                * `marketCap` is in **millions** of USD, as the upstream provider \
                                reports it. 2000 means $2B.
                                * Errors share one shape: `timestamp`, `status`, `message`, plus \
                                `fieldErrors` on validation failures or `provider` on upstream ones.

                                ## Upstream rate limits
                                Market data comes from Twelve Data (800 calls/day, 8/min on the free \
                                tier) and Finnhub. Calls are paced and cached; a cold multi-ticker \
                                scan can therefore take minutes. A 429 from here carries `Retry-After`.
                                """)
                        .contact(new Contact().name("swing-scope"))
                        .license(new License().name("Educational use — not financial advice")))
                .servers(List.of(new Server()
                        .url("http://localhost:8080/swing-scope")
                        .description("Local instance (note the /swing-scope context path)")))
                .tags(List.of(
                        new Tag().name(TAG_CALCULATOR)
                                .description("Position sizing and the rule check. No market data, no state."),
                        new Tag().name(TAG_MARKET_DATA)
                                .description("Price, in-house EMAs, market cap and earnings, assembled "
                                        + "across providers. Support, resistance and the trigger candle "
                                        + "are never returned — those stay human judgment."),
                        new Tag().name(TAG_SCAN)
                                .description("Tier a pasted ticker list against the mechanical filters, "
                                        + "and read the saved watchlist."),
                        new Tag().name(TAG_BACKTEST)
                                .description("Measures whether the suggested levels would have "
                                        + "worked, by replaying them over history. Ranked on "
                                        + "out-of-sample expectancy against a naive ATR baseline."),
                        new Tag().name(TAG_JOURNAL)
                                .description("Read the trade journal and the running scorecard. "
                                        + "Read-only: writes go through the UI.")));
    }
}
