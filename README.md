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
| 3 | Market data (Twelve Data primary, Finnhub secondary) | ✅ Done — DTOs curl-verified 2026-07-29 |
| 4 | Auto-tiering & watchlist scan | ✅ Done |
| 5 | Trade journal + scorecard + graduation tracker | ✅ Done — optional Spring AI (5.9/5.10) skipped |

Full task breakdown: [swing-trade-assistant-implementation-plan.md](swing-trade-assistant-implementation-plan.md).

---

## Tech stack

- Java 17 (enforced via `maven.compiler.release=17`; builds fine on a newer JDK)
- Spring Boot 3.3.5 — Spring Web, Bean Validation, Thymeleaf, Cache (Caffeine), Data JPA
- Spring `RestClient` for provider HTTP
- H2 in file mode (`./data/swing-scope`), swappable to Postgres later
- Maven
- JUnit 5 + AssertJ + MockMvc + MockRestServiceServer, JaCoCo with an **80% line/branch/instruction gate**

All five phases are built. Spring AI (journal narrative, news summary) remains optional and unbuilt.

## Prerequisites

- JDK 17 or newer (developed against JDK 21, compiled to 17 bytecode)
- Maven 3.9+

The calculator (Phases 1–2) and the journal (Phase 5) need no API key — neither makes external
calls. Market data (Phase 3) needs free-tier keys, below.

### Where your data lives

Everything you record — journal entries and the watchlist — is stored in an **H2 database in file
mode**, created automatically on first run:

```
./data/swing-scope.mv.db
```

The path is relative to wherever you start the app, and `data/` is git-ignored.

- **It survives restarts.** Stopping the service does not delete anything; `mvn clean` doesn't touch
  it either, since it lives outside `target/`. The only ephemeral database is the in-memory one the
  test suite uses.
- **To back it up**, copy that one file. To start fresh, delete it — the schema is recreated on the
  next run.
- **A `.lock.db` file** appears alongside it while the app is running. That's normal.
- **Schema changes** are applied by Hibernate on startup (`ddl-auto: update`). It adds columns and
  tables but never rewrites an existing column's type, so if a future change alters one you may need
  to drop `data/` (or ALTER by hand in the console) — the app will tell you with an INSERT error
  rather than silently misbehaving.

Three ways to read the data:

| How | When to use it |
|---|---|
| `GET /api/journal`, `GET /api/watchlist` | Everyday scripted access — see the curl sections below |
| **H2 console** at http://localhost:8080/swing-scope/h2-console | Ad-hoc SQL, fixing a typo by hand |
| Any JDBC tool (DBeaver, IntelliJ) | Bigger queries, exports |

For the console and JDBC tools, connect with:

```
JDBC URL:  jdbc:h2:file:./data/swing-scope
User:      sa
Password:  (blank)
```

`AUTO_SERVER=TRUE` is set, so an external tool can connect **while the app is running**. The console
is bound to localhost (`web-allow-others: false`); it is a live SQL console, so set
`spring.h2.console.enabled: false` if you ever expose this app beyond your machine.

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

---

## The scan — day-to-day workflow (UI)

**http://localhost:8080/swing-scope/scan**

The scan collapses the tedious first steps: paste a ticker list, and the tool fetches the data,
applies the mechanical filters, and hands back a shortlist sorted by how much of your attention each
name deserves. It never tells you what to buy.

### 1. Paste a list, or scan your watchlist

The textarea accepts whatever shape your screener gives you — commas, spaces, newlines, mixed case,
duplicates. Run a screener elsewhere (this tool deliberately doesn't rebuild one), copy the tickers,
paste, hit **Scan list**.

Names you keep coming back to go on the **watchlist** at the bottom of the page; **Scan my watchlist**
then runs the whole set without pasting anything.

### 2. Read the tiers

| Tier | Meaning |
|---|---|
| **Tier 1** | Trend intact, **average** daily volume > 1M **and** cap > $2B — chart these first |
| **Tier 2** | Trend intact but thinner or smaller — tradeable, mind the fill |
| **Tier 3** | Trend intact but event risk today: moved > 5%, or earnings within 3 days |
| **Skip** | Failed the trend test — below the 50-EMA, or 50-EMA below the 200-EMA |
| **Unavailable** | Data couldn't be fetched. Says nothing about the stock |

Every row carries a short plain reason — `below the 50-EMA`, `up 9.3% today — news risk`,
`earnings in 2 days`, `trend intact but thin — 420,000 avg shares/day vs 1,000,000 needed` — so a tier is
never a black box.

### 3. Plan a trade from a row

Tier 1 and Tier 2 rows have a **Plan this trade** link. It opens the calculator with the ticker and
**entry pre-filled from the current price**, and stop and target deliberately blank.

That gap is the whole design. No API sells support and resistance; reading those two levels off the
chart is the judgment this tool keeps human. You type two numbers, the tool does the rest — and from
a PASS you're one click from a journalled trade.

### A note on speed

A cold scan is paced to Twelve Data's free-tier limit of **8 calls a minute**, so a 20-name list can
take a few minutes the first time. Two things make that bearable:

- **Short-circuiting.** A name that fails the trend test costs 2 provider calls instead of 4 — its
  earnings date and market cap are never fetched, because a stock below its 50-EMA is a Skip either
  way.
- **Caching.** Candles are cached 6 hours, quotes 5 minutes. Re-scanning the same list is instant.

---

## The journal — day-to-day workflow (UI)

The journal is the running scorecard and the real-money graduation gate. Open it from the **Journal**
link in the header, or directly:

**http://localhost:8080/swing-scope/journal**

### 1. Plan and journal in one step

Size a trade in the calculator. On a **PASS**, a *"Journal this trade"* button appears under the
result with a setup picker (Breakout / Pullback / Reversal / Range / Other). One click creates a
**PLANNED** entry prefilled with the ticker, entry, stop, target, ratio, shares and risk, and drops
you on its detail page.

Log the trade *when you plan it*, not after it closes — that's the whole point. You can also log one
by hand with **Log a trade** on the journal page.

**A FAIL can be saved too.** When the rules refuse a setup, the button becomes **Save as rejected**
and files it with status `REJECTED` and the refusal reason as its lesson
(*"Rejected by the rules: ratio 1.87 < 2.0"*). Rejected setups are terminal — they can't be filled or
closed — and they stay out of the win rate, expectancy and the graduation count. They are a record
that you passed on something, which is the discipline half of the scorecard.

### 2. Record what actually happened

On the detail page of a PLANNED trade:

- **Mark filled** — enter the real fill price (and the real share count if it differed). Planned
  numbers are kept; the fill is recorded separately, so you can see slippage.
- **Never filled** — marks it NO_FILL. Excluded from the scorecard entirely; it isn't evidence
  about anything.

### 3. Close it

A FILLED trade shows a close form asking for three things:

| Field | Why it's required |
|---|---|
| Exit price | P&L is computed from it |
| Did you follow your rules? | The one question that matters more than the outcome |
| One-sentence lesson | Written while it's fresh, or not at all |

**The outcome is derived, not chosen.** P&L = `(exit − fill) × shares`; positive files as
CLOSED_WIN, negative as CLOSED_LOSS, exactly flat as SCRATCH. A losing trade cannot be recorded as
a win.

### 4. Read the scorecard

The journal page header shows closed-trade count, win rate, net P&L, and expectancy (average dollars
per closed trade). Below it, the graduation tracker with three gates:

1. **25 closed trades**
2. **Positive net total**
3. **Rules followed on every loser**

Only wins and losses count toward any of it — scratches, no-fills and rejected setups are excluded
from the count, the win rate and expectancy. The tracker reports whether your record meets the bar you set; it is
not a recommendation to trade real money.

---

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

### The scan over HTTP

**Tier a pasted list.** `raw` takes the blob exactly as copied; `tickers` takes a parsed array
instead if you have one:

```bash
curl -s -X POST http://localhost:8080/swing-scope/api/scan -H 'Content-Type: application/json' -d '{"raw":"AAPL, MSFT NVDA\nVZ"}'
```

```json
{
  "stocks": [
    {
      "symbol": "AAPL",
      "tier": "TIER1",
      "reason": "trend intact, liquid and established",
      "price": 232.10,
      "changePercent": 1.20,
      "ema20": 229.40, "ema50": 224.00, "ema200": 205.10,
      "distanceToEma50Percent": 3.62,
      "volume": 12480000,
      "averageVolume": 48200000,
      "marketCapMillions": 3510000.00,
      "nextEarningsDate": "2026-10-30",
      "inUptrend": true,
      "bigMover": false,
      "earningsWithin3Days": false
    }
  ],
  "byTier": { "TIER1": [ … ], "SKIP": [ … ] },
  "requested": 4,
  "elapsedMillis": 1840,
  "warnings": []
}
```

Results are sorted best-tier-first, then by distance above the 50-EMA. `byTier` gives the same rows
grouped, which is what the UI renders.

**Scan the saved watchlist** — no body needed:

```bash
curl -s -X POST http://localhost:8080/swing-scope/api/scan/watchlist
```

**Manage the watchlist:**

```bash
curl -s -X POST http://localhost:8080/swing-scope/api/watchlist -H 'Content-Type: application/json' -d '{"ticker":"vz","note":"dividend payer"}'
```

```bash
curl -s http://localhost:8080/swing-scope/api/watchlist
```

```bash
curl -s -X POST http://localhost:8080/swing-scope/api/watchlist/1/note -H 'Content-Type: application/json' -d '{"note":"slow mover"}'
```

```bash
curl -s -X DELETE http://localhost:8080/swing-scope/api/watchlist/1
```

Adding a ticker already on the list is a **no-op, not an error** — re-adding is safe. A blank ticker
returns 400; an unknown id returns 404.

**A scan never fails as a whole.** A ticker whose data can't be fetched comes back as
`"tier": "UNAVAILABLE"` with the reason attached, and the rest of the list is still tiered. With no
API keys configured, every row returns `UNAVAILABLE` with
`no configured provider offers QUOTE — check the API keys in the environment` rather than an error
page.

### The journal over HTTP

The whole lifecycle, end to end. Every command below is real and was run against the app.

**Log a planned trade** — returns `201` with the created entry, including its `id`:

```bash
curl -s -X POST http://localhost:8080/swing-scope/api/journal -H 'Content-Type: application/json' -d '{"ticker":"carr","setupType":"PULLBACK","entry":15.50,"stop":14.75,"target":18.50,"ratio":4.00,"shares":6,"riskAmount":4.50}'
```

**Mark it filled** at the price you actually got (replace `1` with the id):

```bash
curl -s -X POST http://localhost:8080/swing-scope/api/journal/1/fill -H 'Content-Type: application/json' -d '{"fillPrice":15.55,"actualShares":6}'
```

**Close it.** The exit price decides the outcome; the lesson and rules answer are mandatory:

```bash
curl -s -X POST http://localhost:8080/swing-scope/api/journal/1/close -H 'Content-Type: application/json' -d '{"exitPrice":18.50,"lessonText":"waited for the trigger candle instead of anticipating","rulesFollowed":true}'
```

→ `"status": "CLOSED_WIN", "realizedPnl": 17.70` — that is `(18.50 − 15.55) × 6`.

**Save a setup the rules refused** — terminal on arrival, counted nowhere:

```bash
curl -s -X POST http://localhost:8080/swing-scope/api/journal/rejected -H 'Content-Type: application/json' -d '{"ticker":"CI","setupType":"BREAKOUT","entry":20.00,"stop":19.00,"target":21.87,"ratio":1.87,"shares":5,"riskAmount":5.00,"reason":"ratio 1.87 < 2.0"}'
```

→ `"status": "REJECTED", "lessonText": "Rejected by the rules: ratio 1.87 < 2.0"`. The `stats`
endpoint reports these under `rejected`.

**Read the scorecard:**

```bash
curl -s http://localhost:8080/swing-scope/api/journal/stats
```

```json
{
  "totalEntries": 2,
  "openTrades": 1,
  "closedCount": 1,
  "wins": 1,
  "losses": 0,
  "scratches": 0,
  "noFills": 0,
  "winRate": 100.0,
  "netPnl": 17.70,
  "expectancy": 17.70,
  "averageWin": 17.70,
  "averageLoss": 0,
  "losersWithRulesFollowed": 0,
  "graduationTarget": 25,
  "graduationPercent": 4,
  "graduationMet": false
}
```

**Other operations:**

```bash
curl -s http://localhost:8080/swing-scope/api/journal
```

```bash
curl -s -X POST http://localhost:8080/swing-scope/api/journal/1/no-fill
```

```bash
curl -s -X DELETE http://localhost:8080/swing-scope/api/journal/1
```

**Error responses.** An illegal status move returns **409**, not 500 — closing a trade that never
filled gives `"cannot move a trade from PLANNED to CLOSED_WIN"`. An unknown id returns **404**. A
close missing its lesson or rules answer returns **400** with a `fieldErrors` map.

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
| `/swing-scope/scan` | GET, POST | Scan form / tiered results |
| `/swing-scope/scan/watchlist` | POST | Scan the saved watchlist |
| `/swing-scope/plan?ticker=&entry=` | GET | Calculator pre-filled from a scan row |
| `/swing-scope/watchlist` | POST | Add a ticker (UI) |
| `/swing-scope/watchlist/{id}/delete` | POST | Remove a ticker (UI) |
| `/swing-scope/api/scan` | POST | Tier a pasted list |
| `/swing-scope/api/scan/watchlist` | POST | Tier the saved watchlist |
| `/swing-scope/api/watchlist` | GET, POST | List / add watchlist tickers |
| `/swing-scope/api/watchlist/{id}` | DELETE | Remove a watchlist ticker |
| `/swing-scope/api/watchlist/{id}/note` | POST | Update a note |
| `/swing-scope/journal` | GET | Journal list — the running scorecard |
| `/swing-scope/journal/new` | GET | Log-a-trade form |
| `/swing-scope/journal/{id}` | GET | Trade detail — plan, execution, outcome |
| `/swing-scope/journal/{id}/edit` | GET | Edit form |
| `/swing-scope/api/journal` | GET, POST | List / create entries |
| `/swing-scope/api/journal/rejected` | POST | Record a setup the rules refused |
| `/swing-scope/api/journal/stats` | GET | Scorecard totals |
| `/swing-scope/api/journal/{id}` | GET, PUT, DELETE | Fetch / update / delete one entry |
| `/swing-scope/api/journal/{id}/fill` | POST | PLANNED → FILLED |
| `/swing-scope/api/journal/{id}/no-fill` | POST | PLANNED → NO_FILL |
| `/swing-scope/api/journal/{id}/close` | POST | FILLED → CLOSED_* |
| `/swing-scope/api/analyze` | POST | JSON API |
| `/swing-scope/api/marketdata/{symbol}` | GET | Combined snapshot: price, EMAs, cap, earnings |
| `/swing-scope/api/marketdata/search?q=` | GET | Ticker lookup |
| `/swing-scope/api/marketdata/status` | GET | Is the US market open |
| `/swing-scope/h2-console` | GET | SQL console over the journal database (localhost only) |

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

Twelve Data's free tier allows 800 calls a day and 8 a minute; Finnhub allows 60 a minute. Three
mechanisms keep scans inside that budget:

**Pacing.** Every outbound call passes through a sliding-window limiter set from
`marketdata.<provider>.requests-per-minute`. It blocks *before* the request goes out rather than
retrying after a 429, which is both faster and cheaper. Log lines show it: `(paced 4300ms)`.

**Caching.** Caffeine, with a TTL per endpoint:

| Cache | TTL | Why |
|---|---|---|
| `quotes` | 5m | Prices move; the tool is for daily charts |
| `candles` | 6h | Daily bars change once a day |
| `earnings` | 12h | Calendar dates barely move |
| `profile`, `search` | 24h | Market cap and listings are near-static |
| `news` | 1h | Fresh enough to explain today's move |
| `marketStatus` | 10m | |

**Short-circuiting.** A scanned name that fails the trend test never triggers the market-cap and
earnings lookups — 2 calls instead of 4.

Together: a cold 20-name scan is roughly 40 Twelve Data calls (~5 minutes at 8/min); re-scanning the
same list within the cache window costs nothing. A 429 that slips through anyway is retried twice
with doubling backoff before surfacing as HTTP 429 with a `Retry-After` header.

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
  templates/calculator.html, scan.html, journal.html, journal-detail.html, journal-form.html
  templates/fragments/layout.html
  static/css/app.css
  application.yml
```

## Non-goals

No order placement or broker integration. No auto-buy or signal generation. No leverage, options, or
shorting. No real-time streaming — daily data is enough.
