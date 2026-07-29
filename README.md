# swing-scope

A swing-trade **decision-support tool**. "Swing" ties to the strategy, "scope" captures both scanning
and the disciplined narrow focus.

It automates the tedious, deterministic parts — data fetching, risk math, mechanical filtering — and
leaves every judgment call to the human. It may **refuse** to size a trade that fails the rules. It
never places orders, and it never decides what to trade.

> ## ⚠️ Disclaimer
> **Educational tool. Paper trading only. Not financial advice.**
> This software does not execute trades, does not connect to a broker, and does not generate buy or
> sell signals. It reports arithmetic and rule outcomes. Every decision, and every consequence of it,
> is yours. Nothing here is a recommendation to buy or sell any security.

**The human always:** reads the chart, identifies the trigger candle, sets entry/stop/target, and
places the actual (paper) order in the broker.
**The tool always:** computes risk/reward/sizing, applies mechanical filters, and records the journal.

---

## Status

| Phase | Scope | Status |
|---|---|---|
| 1 | Core calculator — sizing math + `POST /api/analyze` | ✅ Done |
| 2 | Thymeleaf calculator UI | ✅ Done |
| 3 | Market data (Twelve Data primary, Finnhub secondary) | ✅ Done — DTOs pending live curl-verification |
| 4 | Auto-tiering & watchlist scan | ⬜ Not started |
| 5 | Trade journal UI + optional Spring AI | ⬜ Not started |

Full task breakdown: [swing-trade-assistant-implementation-plan.md](swing-trade-assistant-implementation-plan.md).

---

## Tech stack

- Java 17 (enforced via `maven.compiler.release=17`; builds fine on a newer JDK)
- Spring Boot 3.3.5 — Spring Web, Bean Validation, Thymeleaf, Cache (Caffeine)
- Spring `RestClient` for provider HTTP
- Maven
- JUnit 5 + AssertJ + MockMvc + MockRestServiceServer, JaCoCo with an **80% line/branch/instruction gate**

Phases 4–5 add Spring Data JPA (H2 file mode) and optionally Spring AI.

## Prerequisites

- JDK 17 or newer (developed against JDK 21, compiled to 17 bytecode)
- Maven 3.9+

The calculator (Phases 1–2) needs no API key — it makes no external calls. Market data (Phase 3
onward) needs free-tier keys, below.

## API keys

Both providers have free tiers. Keys are read from environment variables and must never be committed.

```bash
export TWELVEDATA_API_KEY="your-key"   # https://twelvedata.com  — 800 requests/day free
export FINNHUB_API_KEY="your-key"      # https://finnhub.io      — 60 requests/min free
```

**Why two providers.** Finnhub moved historical candles (`/stock/candle`) to its premium tier, and a
free key gets HTTP 403 there. Candles are what the EMA calculation runs on, so they come from Twelve
Data. Finnhub stays for the endpoints that are still free: earnings calendar, market status, and
company profile (market cap).

Without a key a provider logs a warning at startup and refuses its calls with a clear message —
the app still starts, and the calculator still works.

---

## Running it

Start the app:

```bash
mvn spring-boot:run
```

Then open the calculator in a browser — note the `/swing-scope` context path:

**http://localhost:8080/swing-scope/**

Fill in ticker, entry, stop and target (account size and risk $ are prefilled from config) and hit
**Analyze**. The verdict appears below the form: reward:risk in green when it clears the 2.0 minimum
and red when it doesn't, the share count with the unrounded ideal beside it, and — on a PASS only —
a trade-management panel (time stop, take profit into resistance, trailing rules, earnings check).

A rule failure is *not* an error: the form comes back with a red **FAIL** badge and the reason
("ratio 1.87 < 2.0"). Only structurally invalid input (blank ticker, negative or non-numeric prices)
redisplays the form with per-field messages and no verdict.

### The same thing over HTTP

All API paths sit under the context path too. Analyze a setup:

```bash
curl -s -X POST http://localhost:8080/swing-scope/api/analyze -H 'Content-Type: application/json' -d '{"ticker":"VZ","entry":40.00,"stop":39.00,"target":43.60,"accountSize":500,"riskAmount":5.00}'
```

Response:

```json
{
  "ticker": "VZ",
  "riskPerShare": 1.00,
  "rewardPerShare": 3.60,
  "ratio": 3.60,
  "idealShares": 5.0000,
  "wholeShares": 5,
  "totalRisk": 5.00,
  "positionCost": 200.00,
  "cashLeft": 300.00,
  "pass": true,
  "reason": "PASS"
}
```

A setup that breaks a rule still returns HTTP 200 — the verdict lives in `pass` and `reason`:

```bash
curl -s -X POST http://localhost:8080/swing-scope/api/analyze -H 'Content-Type: application/json' -d '{"ticker":"CI","entry":20.00,"stop":19.00,"target":21.87,"accountSize":500,"riskAmount":5.00}'
```

→ `"pass": false, "reason": "ratio 1.87 < 2.0"`.

Structurally invalid input (negative prices, missing fields, malformed JSON) returns HTTP 400 with a
`fieldErrors` map instead.

### Build and test

```bash
mvn verify
```

`verify` runs the full suite **and** the JaCoCo coverage gate; the build fails below 80%. The HTML
report lands at `target/site/jacoco/index.html`.

Tests only:

```bash
mvn test
```

---

## Routes

Everything is served under the `/swing-scope` context path (`server.servlet.context-path`).

| Route | Method | Purpose |
|---|---|---|
| `/swing-scope/` | GET | Calculator form |
| `/swing-scope/analyze` | POST | Form submit → same page with the verdict |
| `/swing-scope/api/analyze` | POST | JSON API |
| `/swing-scope/api/marketdata/{symbol}` | GET | Combined snapshot: price, EMAs, cap, earnings |
| `/swing-scope/api/marketdata/search?q=` | GET | Ticker lookup |
| `/swing-scope/api/marketdata/status` | GET | Is the US market open |

### `POST /api/analyze`

Request — `riskAmount` is **dollars**, the most you're willing to lose if the stop fills. It is an
absolute figure, not a percentage of the account.

| Field | Type | Rule |
|---|---|---|
| `ticker` | string | not blank |
| `entry` | decimal | > 0 |
| `stop` | decimal | > 0, and below `entry` |
| `target` | decimal | > 0, and above `entry` |
| `accountSize` | decimal | > 0 |
| `riskAmount` | decimal | > 0, dollars at risk (`5.00` = $5) |

Response fields: `riskPerShare`, `rewardPerShare`, `ratio`, `idealShares` (unrounded),
`wholeShares` (tradeable), `totalRisk`, `positionCost`, `cashLeft`, `pass`, `reason`.

### `GET /api/marketdata/{symbol}`

```bash
curl -s http://localhost:8080/swing-scope/api/marketdata/AAPL
```

```json
{
  "symbol": "AAPL",
  "price": 214.25,
  "changePercent": 3.00,
  "ema20": 210.1234,
  "ema50": 205.5678,
  "ema200": 190.9876,
  "volume": 51234567,
  "marketCap": 3250000,
  "nextEarningsDate": "2026-10-30",
  "inUptrend": true,
  "bigMover": false,
  "earningsWithin3Days": false,
  "candlesAvailable": 250,
  "warnings": []
}
```

`inUptrend` is the plain arithmetic of the trend test (price > EMA50 > EMA200) and is **null** when
there isn't enough history to say. `bigMover` is |change%| > 5. `earningsWithin3Days` reflects the
3-calendar-day block rule. None of these is a recommendation — they are the mechanical facts the
human weighs.

Price and candles are required; if either fails the request fails. Market cap and the earnings date
are best-effort — if Finnhub is unconfigured or down, the snapshot still returns with the reason in
`warnings`.

Error responses carry the provider that failed:

| Situation | Status |
|---|---|
| Ticker the provider has no data for | `404` |
| Free-tier budget exhausted | `429` + `Retry-After` |
| Provider disabled, keyless, or endpoint needs a paid plan | `503` |
| Anything else upstream | `502` |

---

## How the sizing works

```
riskPerShare    = entry − stop                      (guard: stop < entry)
rewardPerShare  = target − entry                    (guard: target > entry)
ratio           = rewardPerShare ÷ riskPerShare     (scale 2, HALF_UP)
riskBudget      = riskAmount                        (stated directly in dollars, e.g. $5.00)
idealShares     = riskBudget ÷ riskPerShare
wholeShares     = min( floor(idealShares), floor(accountSize ÷ entry) )
pass            = ratio ≥ 2.0  AND  wholeShares ≥ 1
```

All money is `BigDecimal` — never `double`. Share counts always round **down**, so the realized risk
comes in at or under budget, never over.

`reason` distinguishes the failure modes rather than lumping them together:

| Situation | `reason` |
|---|---|
| Everything clears | `PASS` |
| Position fit the risk budget but not the wallet | `PASS (size capped by available cash, not by the risk budget)` |
| Reward:risk under the minimum | `ratio 1.87 < 2.0` |
| One share costs more than the account holds | `position size is 0 shares — entry price exceeds the account balance` |
| Stop is wider than the whole risk budget | `position size is 0 shares — risk per share exceeds the risk budget` |
| Both the ratio and the size fail | `ratio 1.00 < 2.0, and position size is 0 shares` |
| Stop not below entry | `stop must be below entry` |
| Target not above entry | `target must be above entry` |

---

## Configuration

`src/main/resources/application.yml`:

```yaml
trading:
  rules:
    min-risk-reward: 2.0        # minimum reward:risk to PASS
    default-account-size: 500
    default-risk-amount: 5.00   # dollars at risk per trade
```

Override at runtime, e.g. `--trading.rules.min-risk-reward=3.0`.

`default-risk-amount` only prefills the form — the figure that actually sizes a trade is whatever
you type in the **Risk $** field (or post as `riskAmount`). If you prefer to think in percentages,
work it out yourself and enter the result: 1% of a $500 account is `5.00`.

Provider settings live under `marketdata` — see [application-example.yml](src/main/resources/application-example.yml)
for a copyable version:

```yaml
marketdata:
  twelvedata:
    base-url: https://api.twelvedata.com
    api-key: ${TWELVEDATA_API_KEY:}    # env var, never committed
    retries: 2                          # 429 retries, doubling backoff
    retry-backoff: 500ms
  finnhub:
    enabled: true                       # false to run on Twelve Data alone
  ttl:                                  # cache lifetimes, sized for 800 calls/day
    quote: 5m
    candles: 6h                         # daily bars change once a day
    earnings: 12h
    profile: 24h
```

`application-local.yml`, `application-secrets.yml`, and `.env` are git-ignored.

## Caching and rate limits

Twelve Data's free tier allows 800 calls a day and 8 a minute, so a repeated 20-name watchlist scan
has to come out of cache rather than the wire. Every provider call is cached with a per-endpoint TTL
(Caffeine): quotes 5 minutes, daily candles 6 hours, earnings 12 hours, profiles 24 hours.

A 429 is retried with doubling backoff (2 retries by default) before it surfaces. Every outbound
call is logged with its provider, target, duration, and retry count.

## Logging

- Console and file (`logs/swing-scope.log`), `com.swingscope` at `DEBUG`, root at `INFO`.
- Every HTTP request gets a short correlation id, carried in the MDC and printed in every line of
  that request — so an analysis can be traced end to end.
- The calculator logs the inbound setup, the intermediate math at `DEBUG`, a `WARN` on any rejected
  setup, a note when cash rather than risk capped the size, and the final verdict line.

```
14:02:11.884 INFO  [a3f19c7d] c.s.config.RequestLoggingFilter      - --> POST /api/analyze
14:02:11.901 INFO  [a3f19c7d] c.s.service.TradeCalculatorService   - Analysis complete for VZ: verdict=PASS ratio=3.60 shares=5 ...
14:02:11.903 INFO  [a3f19c7d] c.s.config.RequestLoggingFilter      - <-- POST /api/analyze status=200 in 19ms
```

## Project layout

```
src/main/java/com/swingscope/
  SwingScopeApplication.java
  config/    TradingRules, MarketDataProperties, CacheConfig, RequestLoggingFilter
  domain/    TradeSetup, TradeAnalysis
    marketdata/  Quote, Candle(s), EarningsEvent, CompanyProfile,
                 MarketStatus, SymbolMatch, MarketSnapshot
  service/   TradeCalculatorService — all the math
    marketdata/  MarketDataProvider (capability-based interface),
                 AbstractRestProvider (logging + 429 backoff),
                 MarketDataService (routing + snapshot assembly),
                 EmaCalculator, exceptions
      twelvedata/  TwelveDataClient + DTOs   (primary)
      finnhub/     FinnhubClient + DTOs      (secondary)
  web/       TradeAnalysisController (JSON), WebController (UI),
             MarketDataController, TradeSetupForm, ApiExceptionHandler

src/main/resources/
  templates/calculator.html, templates/fragments/layout.html
  static/css/app.css
  application.yml
```

## Non-goals

No order placement or broker integration. No auto-buy or signal generation. No leverage, options, or
shorting. No real-time streaming — daily data is enough.
