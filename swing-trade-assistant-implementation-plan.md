# Swing Trade Assistant — Implementation Plan

## Table of Contents
1. [Purpose & Guiding Principle](#purpose--guiding-principle)
2. [Tech Stack](#tech-stack)
   - [Data provider choice (Twelve Data + Finnhub)](#important--data-provider-choice)
3. [Domain Rules](#domain-rules-encode-these-as-constantsconfig)
4. [Phase & Task Status Overview](#phase--task-status-overview)
5. [Phase 1 — Core Calculator](#phase-1--core-calculator-no-external-calls-pure-logic)
6. [Phase 2 — Thymeleaf UI for the Calculator](#phase-2--thymeleaf-ui-for-the-calculator)
7. [Phase 3 — Market Data Integration](#phase-3--market-data-integration-twelve-data--optional-finnhub)
8. [Phase 4 — Auto-Tiering & Watchlist Scan](#phase-4--auto-tiering--watchlist-scan)
9. [Phase 5 — Journal + UI + (optional) Spring AI](#phase-5--journal--ui--optional-spring-ai)
10. [Cross-Cutting Tasks](#cross-cutting-tasks)
11. [Suggested Build Order](#suggested-build-order)
12. [Explicit Non-Goals](#explicit-non-goals-do-not-build)

---

## Purpose & Guiding Principle
A **decision-support tool**, not an autotrader. It automates the tedious, deterministic parts (data fetching, math, filtering) and leaves every *judgment* and the trade decision to the human. The tool may **refuse** to size a trade that fails the rules, but it never places orders and never decides what to trade.

**The human always:** reads the chart, identifies the trigger candle, sets entry/stop/target, and places the actual (paper) order in the broker.
**The tool always:** fetches data, computes risk/reward/sizing, applies mechanical filters (trend, liquidity, big-mover, earnings), and records the journal.

---

## Tech Stack
- **Java 17** (LTS)
- **Spring Boot 3.2.x or 3.3.x** (both run on Java 17; 3.2/3.3 baseline is Java 17, so this is the natural fit). Avoid Spring Boot 3.5+ only if it ever raises the baseline — as of the 3.2/3.3 line, Java 17 is fully supported.
- Spring Web (REST), Spring `RestClient` for HTTP (available since Spring Framework 6.1 / Boot 3.2)
- Thymeleaf for lightweight server-rendered UI
- Spring AI — **optional, Phase 5 only** (news summary + journal narrative). Not used for math or decisions. Use a Spring AI version aligned with Boot 3.2/3.3 on Java 17.
- Persistence: start with H2 (file mode) via Spring Data JPA; trivially swappable to Postgres later
- Build: Maven or Gradle (set `<java.version>17</java.version>` / `sourceCompatibility = 17`)
- Third-party data: **Twelve Data** free tier (primary), **Finnhub** free tier (secondary/optional).

**Language-level note for Claude Code:** target Java 17 features only — records, sealed classes, pattern matching for `instanceof`, switch expressions, text blocks are all fine. Do NOT use Java 21-only features (e.g. unnamed patterns/variables, virtual threads via `Executors.newVirtualThreadPerTaskExecutor` as a hard dependency, record patterns in `switch` which finalized in 21). Keep the codebase compiling cleanly on JDK 17.

### IMPORTANT — data provider choice
Finnhub **moved historical candles (`/stock/candle`) to its premium tier**; a free key returns HTTP 403. The free Finnhub key still covers quote, company profile, earnings, company news, and symbol search — but NOT candles, so it cannot feed the EMA calculation on its own. Therefore candles/EMAs come from Twelve Data.

**Primary provider: Twelve Data.** Free tier = **800 requests/day** (plenty for daily-chart scanning of a ~20-name watchlist). Base URL `https://api.twelvedata.com`, `apikey` query param on every call.
| Purpose | Twelve Data endpoint |
|---|---|
| Current/last price + change | `/quote?symbol=SYM` |
| Daily candles (OHLCV history) | `/time_series?symbol=SYM&interval=1day&outputsize=250` |
| EMA (pre-computed, cross-check) | `/ema?symbol=SYM&interval=1day&time_period=50` |
| Symbol lookup | `/symbol_search?symbol=NAME` |

**EMA strategy:** compute EMA20/50/200 **in-app** from `/time_series` daily closes via `EmaCalculator`, so settings exactly match the user's E*TRADE chart AND to spend fewer of the 800 daily requests. Use Twelve Data's `/ema` only as an occasional cross-check.

**Secondary provider: Finnhub (optional).** Use its still-free endpoints where convenient. Do NOT use it for candles/EMAs.
| Purpose | Finnhub endpoint |
|---|---|
| Earnings calendar (3-day rule) | `/calendar/earnings?from=DATE&to=DATE&symbol=SYM` |
| Company news (Phase 5 AI summary) | `/company-news?symbol=SYM&from=DATE&to=DATE` |
| Symbol search (validate ticker) | `/search?q=NAME` |
| Market status (is market open) | `/stock/market-status?exchange=US` |

**Design note for Claude Code:** put providers behind a single `MarketDataProvider` interface with `TwelveDataClient` and `FinnhubClient` implementations, so sources can be swapped or split by capability without touching services. **Verify each endpoint's live JSON with curl before writing DTOs — field names and response shapes differ between providers.** Support/resistance and the trigger candle are NOT fetched from any API — they stay human judgment.

---

## Domain Rules (encode these as constants/config)
- Account size and **risk in dollars** are configurable. Default: account $500, risk **$5.00**/trade (the old "1% of $500", stated directly). The user enters dollars; the app does no percentage conversion. *(Changed from `riskPct` on 2026-07-28 and shipped — this section had reverted to the percentage wording, but the code, UI and API are all dollars.)*
- Min risk/reward ratio: 2.0 (configurable).
- Position size = floor( riskAmount ÷ (entry − stop) ), then cap by available cash. Take the smaller.
- Round share count DOWN to whole shares (fractional support is broker-dependent; assume whole).
- Trend test: price > EMA50 AND EMA50 > EMA200 (uptrend). Else Skip.
- Big-mover flag: abs(dailyChange%) > 5 → Tier 3 (news risk).
- Earnings flag: earnings date within 3 calendar days → block trade.
- Long only. No shorting logic anywhere.
- The tool NEVER outputs "buy this." It outputs analysis + PASS/FAIL + reason.

---

## Phase & Task Status Overview

Status legend: ☐ Not started · ◐ In progress · ☑ Done

| Phase | # | Task | Status |
|-------|---|------|--------|
| **1 — Core Calculator** | 1.1 | Project skeleton (domain/service/web/config packages) | ☑ |
| | 1.2 | Domain records: `TradeSetup`, `TradeAnalysis` | ☑ |
| | 1.3 | `TradeCalculatorService.analyze()` with guards | ☑ |
| | 1.4 | `BigDecimal` money handling throughout | ☑ |
| | 1.5 | Unit tests (VZ, CI-fail, CARR, stop>entry, cash-cap) | ☑ |
| | 1.6 | REST endpoint `POST /api/analyze` | ☑ |
| **2 — Calculator UI** | 2.1 | Thymeleaf dep + `WebController` (`GET /`, `POST /analyze`) | ☑ |
| | 2.2 | `calculator.html` form + results table | ☑ |
| | 2.3 | Ratio color-coding (red < 2, green ≥ 2) | ☑ |
| | 2.4 | Management-rules panel on PASS | ☑ |
| | 2.5 | Base layout + stylesheet | ☑ |
| **3 — Market Data Integration** | 3.1 | Config + `@ConfigurationProperties` (Twelve Data + optional Finnhub keys via env) | ☑ |
| | 3.2 | `MarketDataProvider` interface + `TwelveDataClient` (primary) + `FinnhubClient` (secondary) | ☑ |
| | 3.3 | DTOs matched to real JSON per provider (curl-verify first) | ☑ |
| | 3.4 | Error handling (429 backoff, status:error/no_data, unknown symbol, Finnhub 403) | ☑ |
| | 3.5 | `EmaCalculator` from Twelve Data closes + hand-checked test | ☑ |
| | 3.6 | Caching (`@Cacheable`, respect 800/day) | ☑ |
| | 3.7 | `GET /api/marketdata/{symbol}` snapshot (assembled across providers) | ☑ |
| **4 — Auto-Tiering & Scan** | 4.1 | `TierService.tier(tickers)` with rule engine | ☑ |
| | 4.2 | `Watchlist` entity + CRUD + UI list | ☑ |
| | 4.3 | `scan.html` (paste tickers / scan watchlist → tiered table) | ☑ |
| | 4.4 | "Plan this trade" prefills calculator | ☑ |
| | 4.5 | Rate-limit-aware batching | ☑ |
| **5 — Journal + UI + AI** | 5.1 | `TradeJournalEntry` entity + repository | ☑ |
| | 5.2 | Journal CRUD REST endpoints | ☑ |
| | 5.3 | **Journal UI: list view = running scorecard** | ☑ |
| | 5.4 | **Journal UI: detail view (plan + execution + outcome)** | ☑ |
| | 5.5 | **Journal UI: create/edit form + status transitions** | ☑ |
| | 5.6 | Auto-computed P&L + win-rate/expectancy summary | ☑ |
| | 5.7 | Graduation tracker (progress to 25–30, positive total) | ☑ |
| | 5.8 | "Plan this trade" → auto-creates a PLANNED journal entry | ☑ |
| | 5.9 | (optional) Spring AI news-summary endpoint | ☐ deferred |
| | 5.10 | (optional) Spring AI journal-narrative endpoint | ☐ deferred |
| **6 — Suggested Levels** | 6.1 | `SwingPointDetector` (fractal pivots) | ☑ |
| | 6.2 | `AtrCalculator` (ATR-14) | ☑ |
| | 6.3 | `PriceLevelService` — cluster pivots into scored zones | ☑ |
| | 6.4 | `LevelSuggestionService` — stop/target candidates + rationale | ☑ |
| | 6.5 | Refusal guards (too few bars, no pivot, stop too wide) | ☑ |
| | 6.6 | Journal `levelSource` provenance + scorecard breakdown | ☑ |
| | 6.7 | `GET /api/levels/{symbol}` + suggested prefill in `/plan` | ☑ |
| | 6.8 | Inline SVG level chart on the calculator | ☑ |
| | 6.9 | `LevelProperties` — all thresholds configurable | ☑ |
| **6A — Backtest harness** | 6A.1 | Backtest result records (R-based metrics) | ☑ |
| | 6A.2 | `LevelBacktestService.replay()` — walk-forward, no lookahead | ☑ |
| | 6A.3 | Conservative resolvers (intrabar → stop, gap → fill at open) | ☑ |
| | 6A.4 | **Lookahead-bias test** | ☑ |
| | 6A.5 | `ParameterSweep` ranked by out-of-sample expectancy | ☐ |
| | 6A.6 | In-sample / out-of-sample split, both reported | ☐ |
| | 6A.7 | `POST /api/backtest` + results page with caveats on-screen | ☐ |
| | 6A.8 | Adopt winning params into `LevelProperties` with justification recorded | ☐ |
| **Cross-cutting** | X.1 | Config & secrets (env var, example yml, git-ignore) | ☑ |
| | X.2 | Bean Validation on `TradeSetup` | ◐ field-level done; cross-field stop<entry<target lives in the service, not as a constraint |
| | X.3 | Tests: calculator + EMA + provider clients (MockRestServiceServer) | ☑ |
| | X.4 | README + disclaimer | ☑ |
| | X.5 | Logging (external calls + rate-limit hits) | ☑ |

---

## PHASE 1 — Core Calculator (no external calls, pure logic)
**Goal:** type in entry/stop/target/account → get risk, reward, ratio, shares, verdict. Immediately useful, no API needed.

### Tasks
1. Project skeleton: Spring Boot app, package structure `domain`, `service`, `web`, `config`.
2. Domain records:
   - `TradeSetup(String ticker, BigDecimal entry, BigDecimal stop, BigDecimal target, BigDecimal accountSize, BigDecimal riskAmount)`
   - `TradeAnalysis(riskPerShare, rewardPerShare, ratio, idealShares, wholeShares, totalRisk, positionCost, cashLeft, boolean pass, String reason)`
3. `TradeCalculatorService.analyze(TradeSetup)`:
   - Compute riskPerShare = entry − stop; guard: stop < entry, else FAIL "stop must be below entry".
   - rewardPerShare = target − entry; guard: target > entry.
   - ratio = reward/risk (scale 2, HALF_UP).
   - maxRisk = riskAmount (dollars, as entered — no percentage conversion).
   - idealShares = maxRisk / riskPerShare; wholeShares = floor(idealShares).
   - Cap wholeShares so positionCost ≤ accountSize.
   - pass = ratio ≥ 2.0 AND wholeShares ≥ 1.
   - Populate reason (e.g., "ratio 1.85 < 2.0" or "PASS").
4. Use `BigDecimal` everywhere for money; never `double`.
5. Unit tests (JUnit 5): the VZ case (1:3.6), the CI case (1.87 FAIL), the CARR case, a stop-above-entry error case, a cash-cap case (expensive stock → shares capped/zero).
6. REST controller: `POST /api/analyze` accepts `TradeSetup` JSON, returns `TradeAnalysis`.

**Deliverable:** working endpoint + green tests. Can be used manually day one.

---

## PHASE 2 — Thymeleaf UI for the Calculator
**Goal:** a browser form instead of raw JSON.

### Tasks
1. Thymeleaf dependency + a `WebController` serving `GET /` (form) and `POST /analyze` (result).
2. `calculator.html`: inputs for ticker, entry, stop, target, account, risk $ (defaults 500 / $5.00). Submit → results table (risk, reward, ratio, shares, position cost, PASS/FAIL with color).
3. Client-side niceties: show ratio in red if < 2, green if ≥ 2.
4. A "management rules" panel rendered on any PASS result: time-stop (15 trading days), take-profit-into-resistance, trailing-stop reminders (static text).
5. Basic layout/CSS (single stylesheet, no framework needed).

**Deliverable:** usable local web app for sizing any trade in seconds.

---

## PHASE 3 — Market Data Integration (Twelve Data + optional Finnhub)
**Goal:** fetch price/candles/earnings/market-status so the user stops typing raw numbers and the tool can pre-filter.

### Tasks
1. Config: provider keys + base URLs in `application.yml` (`twelvedata.api-key`, `twelvedata.base-url`, and optional `finnhub.*`); bind via `@ConfigurationProperties`. Keys from env vars, never committed.
2. `MarketDataProvider` interface, with two implementations using Spring `RestClient`:
   - **`TwelveDataClient` (primary):**
     - `Quote getQuote(String symbol)` → price, change%.
     - `Candles getDailyCandles(String symbol, int outputSize)` → daily OHLCV from `/time_series` (used for EMA + candle reading).
     - `List<SymbolMatch> search(String query)`.
   - **`FinnhubClient` (secondary, optional):**
     - `List<EarningsEvent> getEarnings(String symbol, LocalDate from, LocalDate to)`.
     - `List<NewsItem> getCompanyNews(String symbol, LocalDate from, LocalDate to)` (for Phase 5 AI).
     - `MarketStatus getMarketStatus()`.
3. DTOs matched to the ACTUAL JSON per provider (instruct: hit each endpoint with curl first, map fields to what returns — Twelve Data and Finnhub have different shapes; do not assume field names).
4. Error handling: rate-limit (HTTP 429) backoff, empty/`status:error`/`no_data` responses, unknown symbol, and Finnhub 403 on premium endpoints. Wrap in a `MarketDataException`.
5. `EmaCalculator` utility: standard EMA over a close-price series for period N (multiplier 2/(N+1), seed with SMA of first N). Compute EMA20/50/200 from Twelve Data daily closes. **Add a test that reproduces a known EMA by hand** so it matches the chart.
6. Caching: cache quotes/candles for a few minutes (Spring `@Cacheable` + simple in-memory) to respect the 800/day limit when scanning ~20 names.
7. Endpoint `GET /api/marketdata/{symbol}` returning a combined snapshot (price, change%, EMA20/50/200, volume, nextEarningsDate) — assembled across providers behind the `MarketDataProvider` abstraction.

**Deliverable:** given a ticker, the app returns a full data snapshot with EMAs computed in-house.

### Phase 3 — Status: ☑ Done (121 tests green; 98.9% instruction / 92.2% branch / 98.8% line)
Carry-forward notes for Phase 4:
- **Capability routing.** `MarketDataProvider` declares a `Capability` enum (QUOTE, DAILY_CANDLES, SYMBOL_SEARCH, EARNINGS, MARKET_STATUS, COMPANY_PROFILE, COMPANY_NEWS); unimplemented methods default to throwing `ProviderUnavailableException`. `MarketDataService.provider(capability)` picks the first available provider offering it. **`TierService` must depend on `MarketDataService`, never on a client directly.**
- **Split:** Twelve Data = quote + candles + search. Finnhub = earnings + market status + profile + news. Finnhub deliberately does not declare DAILY_CANDLES.
- **3.3 curl-verified against live JSON on 2026-07-29.** Every mapped field name matched; no DTO changes were needed. Traps confirmed: `/time_series` is **newest-first** (the client's chronological flip is load-bearing); Twelve Data numerics are **JSON strings** including volume; an unknown symbol on `/quote` is a real **HTTP 404**; Finnhub `/stock/profile2` answers an unknown ticker with **200 and `{}`**; `/stock/candle` returns **403** as expected. `/company-news` is the one endpoint mapped from documentation only — not yet curl-verified.
- **`marketCapitalization` is a float in millions** (AAPL ≈ 4,994,876 → ~$4.99T). Phase 4's TIER1/2 market-cap threshold must compare in millions, or scale first. This is the easiest thing in the whole phase to get wrong by 10⁶.
- **`inUptrend` is a `Boolean`** — `null` means "fewer than 200 bars, test inconclusive". Phase 4 must treat null as SKIP-with-reason, not as false.
- **Snapshot policy:** quote and candles are required (failure propagates); market cap and earnings are best-effort and degrade into `warnings`. News is never fetched by the snapshot — it is context for the human and Phase 5's AI, and no filter or sizing rule consults it.
- **Caching:** Caffeine, per-endpoint TTLs (quote 5m, candles 6h, earnings 12h, profile/search 24h, news 1h, status 10m), sized against Twelve Data's 800/day and 8/min. 429s retry twice with doubling backoff. Phase 4's batching (4.5) should lean on these rather than adding its own throttle.
- **HTTP mapping:** unknown symbol → 404, rate limit → 429 + `Retry-After`, unconfigured/premium → 503, anything else upstream → 502.
- **Java 17 note:** `BigDecimal.TWO` is Java 19+ and broke the build once; use a local constant.

---

## PHASE 4 — Auto-Tiering & Watchlist Scan
**Goal:** paste a whole Finviz ticker list in ONE go → tool fetches data for every ticker, computes the mechanical filters, and returns a pre-tiered shortlist. Collapses the first several manual steps into one action.

### What the tool automates vs. what stays human (read first)
- **Tool auto-provides:** entry price (= current fetched price), EMA20/50/200, volume, change%, earnings date, the trend/liquidity/mover/earnings filters, tiering, and ALL risk/reward/sizing math.
- **Human still provides (2 inputs per candidate):** **stop** (just below the support YOU identify on the chart) and **target** (the nearest horizontal resistance YOU identify). No API supplies support/resistance — and this is intentional: reading the chart to set these two levels IS the judgment we keep human. The tool never guesses them.
- Net effect: instead of typing 4 numbers and doing all the math by hand, you paste a list, eyeball the Tier-1 charts, and type 2 numbers (stop, target) for each real candidate.

### Tasks
1. `TierService.tier(List<String> tickers)`:
   - Accept a **batch** of tickers (the full pasted Finviz list, e.g. 10–25 symbols) in one call.
   - For each: fetch snapshot (Phase 3), respecting the rate limit via cache + batching.
   - Apply rules → `SKIP` (price < EMA50, or EMA50 < EMA200), `TIER3` (|change%| > 5, or earnings within 3 days), else `TIER1/2` split by volume/market cap.
   - Return `List<TieredStock>` with all data + tier + short machine reason ("below 50-EMA", "up 9.3% today — news risk", "earnings in 2 days").
2. Persist a `Watchlist` entity (stable ~15–20 names) so recurring names aren't re-pasted. CRUD + simple UI list.
3. `scan.html`:
   - A **textarea to paste the whole ticker list at once** (comma/space/newline separated) OR a "scan my watchlist" button.
   - Submit → one request tiers the entire batch → results table grouped by tier, each row showing price (=entry), EMA distances, change%, earnings date, tier reason.
   - Tier-1 rows have a "Plan this trade" action.
4. "Plan this trade" opens the calculator **pre-filled with entry = current price**; the user types only **stop** and **target** (from their chart reading); tool instantly returns risk/reward/ratio/shares/verdict.
5. Rate-limit-aware batching (respect the 800/day Twelve Data budget; sequence calls with the cache so a 20-ticker scan stays well within limits).

**Deliverable:** one click → a tiered shortlist. Human only charts the Tier-1 names and enters stop/target.

### Phase 4 — Status: ☑ Done (231 tests green; 97.8% instruction / 88.9% branch / 97.4% line)
Decisions taken (answers to what this section left open):
- **Tier 1 needs BOTH** average volume > **1,000,000** AND market cap > **$2B**; failing either drops it to Tier 2. Configurable via `scan.tier1-min-volume` and `scan.tier1-min-market-cap-millions`.
- **Liquidity is judged on `averageVolume`, not `volume`.** Shipped wrong first time: the snapshot carried only today's running session volume, so a live scan demoted COST ($428B cap) to Tier 2 for "169,476 shares" — a partial mid-session figure against its ~2M daily average. `MarketSnapshot` now carries both; the filter uses the average and falls back to today's only when the provider omits it. Regression test: `liquidityUsesAverageVolumeNotTodays`.
- **Market cap is compared in MILLIONS** (`2000` = $2B), matching what Finnhub returns. This was the single easiest thing in the phase to get wrong by 10⁶ — there is a test named `marketCapThresholdIsInMillions` pinning it.
- **Rule order matters and is load-bearing:** trend test → earnings-within-3-days → big-mover → liquidity/size split. Earnings outranks the big-mover flag, so a stock that is both reports "earnings in 2 days" (the blocking reason) rather than the move.
- **`null` inUptrend is SKIP with its own reason** ("not enough history … inconclusive"), never silently treated as false.
- **Short-circuiting (4.5):** a name failing the trend test never triggers the market-cap and earnings lookups — 2 provider calls instead of 4.
- **Pacing (4.5):** a sliding-window `RateLimiter` in `AbstractRestProvider` blocks *before* each call, set from `marketdata.<provider>.requests-per-minute` (Twelve Data 8, Finnhub 60). Blocking beats being 429'd. A cold 20-name scan is ~40 Twelve Data calls ≈ 5 minutes; repeats come from cache.
- **Batch ceiling:** `scan.max-tickers-per-scan` (default 30). Over that, the list is truncated with a warning rather than running for an hour.
- **A scan never fails as a whole** — an unfetchable ticker becomes `Tier.UNAVAILABLE` with the provider's message, and the other rows still tier. `UNAVAILABLE` is explicitly *not* a verdict about the stock.
- **"Plan this trade" (4.4)** is `GET /plan?ticker=&entry=`, pre-filling entry from the current price and leaving **stop and target blank on purpose**. Only Tier 1/2 rows offer the link.
- **Watchlist:** adding a ticker already present is a **no-op, not an error**, so re-adding is safe.

**Still open:** the screener itself is deliberately not built (see *Screener Sourcing* above) — Option 1, paste from Finviz, is what the scan textarea expects.

---

## PHASE 5 — Journal + UI + (optional) Spring AI
**Goal:** a fully UI-integrated trade journal that records every planned/placed trade and its outcome, doubles as the running scorecard, gates real-money graduation, and (optionally) auto-drafts narrative via Spring AI.

### Tasks
1. `TradeJournalEntry` entity: ticker, setupType, entry/stop/target, ratio, shares, riskAmount, status (PLANNED / FILLED / NO_FILL / CLOSED_WIN / CLOSED_LOSS / SCRATCH), fillPrice, exitPrice, realizedPnl, datePlanned, dateFilled, dateClosed, lessonText, rulesFollowed (boolean). JPA + repository.
2. Journal CRUD REST endpoints (`/api/journal`), used by the UI and by the "Plan this trade" flow.
3. **Journal UI — list view (`journal.html`):** the running scorecard. Table of all entries (# / ticker / setup / status / P&L / rules-followed), color-coded by status, sortable by date. Header shows totals: closed-trade count, win rate, net P&L, expectancy.
4. **Journal UI — detail view (`journal-detail.html`):** one trade's full lifecycle — the plan (entry/stop/target/ratio/shares), execution (fill price, actual shares), outcome (exit, P&L, hit target/stop/time), and the one-sentence lesson. Editable inline.
5. **Journal UI — create/edit form (`journal-form.html`):** manual entry plus status-transition controls (PLANNED → FILLED → CLOSED_*). On close, prompt for exit price, auto-compute P&L, and require the lesson + rules-followed fields.
6. Auto-computed realized P&L per closed trade and a rolling win-rate / expectancy summary on the list view.
7. Graduation tracker widget: progress bar toward 25–30 closed trades with a positive net total and rules-followed on losers.
8. **Integration:** the Phase 4 "Plan this trade" action creates a `PLANNED` journal entry automatically (prefilled from the calculator), so planning and journaling are one step, not two. Closing the loop end to end: scan → plan (auto-journaled) → user places order in broker → user updates status/outcome in the journal UI.
9. **Spring AI (optional):**
   - `POST /api/ai/news-summary/{symbol}` → pull recent `/company-news`, summarize *why* a stock moved (context only — explicitly NOT a trade signal).
   - `POST /api/ai/journal-narrative/{id}` → turn a structured journal entry into a one-paragraph note, surfaced on the detail view.
   - Guardrail in prompt + code: AI never recommends buying/selling; it summarizes and narrates only.

**Deliverable:** a browser-based journal that is the single source of truth for the scorecard and the real-money graduation gate, wired directly into the scan/plan flow.

### Phase 5 — Status: ☑ Done, 5.1–5.8 (173 tests green; 98.2% instruction / 91.6% branch / 98.0% line)
Built **out of the suggested order** — Phase 4 is still unstarted, so 5.8's "Plan this trade" hand-off currently runs from the Phase 2 calculator (`POST /journal/from-calculator`) rather than from `scan.html`. Phase 4 should post to that same endpoint instead of adding a second path.

Decisions taken (answers to the questions this plan left open):
- **P&L** = `(exitPrice − fillPrice) × shares`, long only, **no commissions**. **Expectancy** = net P&L ÷ closed-trade count, i.e. average dollars per completed trade.
- **Only CLOSED_WIN and CLOSED_LOSS count.** SCRATCH and NO_FILL are excluded from the count, the win rate, expectancy and the graduation gate — neither is evidence about the strategy. `TradeStatus.isCountedTrade()` is the single place this is decided.
- **`setupType` is an enum** — BREAKOUT / PULLBACK / REVERSAL / RANGE / OTHER — so the scorecard can group by setup later.
- **The outcome is derived, never chosen.** `close()` computes P&L and assigns CLOSED_WIN / CLOSED_LOSS / SCRATCH from its sign, so a loser cannot be filed as a win. Exactly break-even is a SCRATCH.
- **Closing requires exit price + lesson + rules-followed.** Enforced in the service, not just the form, so the API cannot skip them either.
- **Legal transitions only:** PLANNED → FILLED | NO_FILL, FILLED → CLOSED_*. Anything else throws `InvalidTransitionException` → HTTP **409** on the API, a flash error in the UI.
- **Graduation = 25 closed trades AND positive net AND rules followed on every loser.** All three, or the gate stays shut. It reports a fact about the record; it is not permission to trade.
- **Persistence:** H2 file mode at `./data/swing-scope` (git-ignored), `ddl-auto: update`. Tests run on in-memory H2 via `@ActiveProfiles("test")` so they never touch the real file — **any new `@SpringBootTest` must carry that annotation.**
- **Deferred:** 5.9/5.10 (Spring AI news summary + journal narrative). They need an LLM key and a Spring AI dependency; the journal is finished without them. `MarketDataService.getRecentNews()` already exists as the input for 5.9.

### Post-Phase-5 additions (2026-07-29)
- **`REJECTED` status.** The calculator offers "Save as rejected" on a FAIL, filing the setup with the refusal reason as its lesson. Terminal on arrival — cannot be filled or closed — and excluded from win rate, expectancy, the graduation count *and* the open-trade count. Exposed as `POST /api/journal/rejected`. `JournalStats.rejected` reports the tally.
- **H2 console enabled**, localhost-only (`web-allow-others: false`) at `/swing-scope/h2-console`. Turn it off before exposing the app anywhere.
- **Bug fixed: liquidity used today's volume.** `MarketSnapshot.volume` is the running session total, partial mid-day; the Tier 1 test now uses `averageVolume` (falling back to today's only when the provider omits it). This was demoting mega-caps to Tier 2 — COST showed 169k shares against a ~2M average. `MarketSnapshot` now carries both.
- **Bug fixed: enum columns were native H2 ENUMs.** Hibernate maps a Java enum to H2's native ENUM type, which fixes the permitted values at creation time; `ddl-auto: update` never widens it. Adding `REJECTED` therefore failed at INSERT on any pre-existing database (`Value not permitted for column ...`). Both enum columns are now pinned with `columnDefinition = "varchar(20)"`, so future constants need no migration. **The test suite could not catch this** — it runs `create-drop` on in-memory H2, where the schema is always rebuilt from the current enum. `EnumColumnMappingTest` now guards the mapping. Any future schema change that alters a column type still needs a manual migration or a dropped `data/`.
- **Bug fixed: table buttons rendered invisible.** `.journal-table a` (specificity 0,1,1) beat `.button-link` (0,1,0) and painted "Plan this trade" accent-blue on its own accent-blue background. Fixed with `.journal-table a.button-link`, guarded by `ScanStylesheetTest` — HTML-level tests could not catch it because the markup was correct.

### Controller consolidation (2026-07-29)
The HTTP surface was **7 controllers / 39 endpoints**, because the plan specified REST endpoints (1.6, 3.7, 5.2 — "used by the UI") while the UI was built as server-rendered Thymeleaf forms that never called them. Result: 11 write operations implemented twice.

Now **4 controllers / 30 endpoints**, one per feature:
- `CalculatorController` (was `WebController` + `TradeAnalysisController`)
- `ScanController` (was `ScanWebController` + `ScanApiController`)
- `JournalController` (was `JournalWebController` + `JournalApiController`)
- `MarketDataController` (unchanged)

**The `/api/**` surface is read-only.** Removed: `POST/PUT/DELETE /api/journal*` (create, update, fill, no-fill, close, delete, rejected) and `POST /api/watchlist`, `DELETE /api/watchlist/{id}`. `JournalRequests` went with them. Kept because they compute rather than mutate: `POST /api/analyze`, `POST /api/scan`, `POST /api/scan/watchlist`. Kept because it has no UI equivalent: `POST /api/watchlist/{id}/note`.

**API documentation (2026-07-30).** springdoc-openapi 2.6 generates an OpenAPI 3 spec from the controllers; Swagger UI at `/swing-scope/swagger-ui.html`, raw spec at `/v3/api-docs`. Scoped with `springdoc.paths-to-match: /api/**` so the Thymeleaf form handlers stay out — they return HTML, and documenting them as an API would be a lie to the caller. `OpenApiDocumentationTest` asserts all 11 operations are present, that each has a summary *and* a description, and that no non-`/api` path leaks in. Descriptions deliberately cover the traps: rule failures are 200s, `inUptrend: null` ≠ false, market cap is in millions, cold scans block for minutes. Domain records are *not* annotated with `@Schema` — the endpoint docs carry the meaning.

**Rule for new work:** a write belongs on the UI controller only. Add a JSON write endpoint solely when there is no UI path to the same operation, and say why in a comment.

**Open question for Phase 4/5 integration:** partial exits are still not modelled — one fill price, one exit price, one share count. The Phase 2 management-rules panel tells the user "partial exits are fine", so either the journal needs to handle them or that wording should change.

---


---

## PHASE 6 — Suggested Stop & Target Levels (automating the last manual step)

**Goal:** compute *candidate* stop and target levels from the daily candles already in cache, so the two remaining manual inputs become confirm-or-override rather than read-the-chart-from-scratch.

### ⚠️ This reverses a stated principle — read before building
Phases 1–5 were built on: *"reading the chart to set these two levels IS the judgment we keep human. The tool never guesses them."* Phase 6 changes that, and moves toward the non-goal *"no signal generation."* The design below keeps the reversal honest rather than silent:

- **Propose, never decide.** Suggested levels prefill the calculator marked as suggestions, with reasoning attached. Nothing is sized until the human confirms or overrides.
- **Refuse rather than guess.** If no clean pivot exists in the window, return no suggestion and say why. An invented level is worse than a blank field.
- **Show the working.** Every suggestion carries the evidence (which pivots, how many touches, how recent) and a small chart. The human sanity-checks in seconds instead of measuring from zero.
- **Record provenance.** The journal stores whether levels were HUMAN, SUGGESTED or EDITED, so the scorecard can eventually answer "are my levels better than the computed ones?" Without this the experiment is unfalsifiable.

### Key enabler (verified 2026-07-30)
`MarketDataService.getDailyCandles(symbol, 250)` is already called for every scanned ticker and cached for 6h. Level detection reads those same bars — **zero extra provider calls** for anything already scanned, so the 8/min free-tier ceiling is untouched.

### Tasks

| # | Task | Notes |
|---|---|---|
| 6.1 | `SwingPointDetector` — fractal pivot detection over `List<Candle>` | A swing low is a bar whose low is below the `n` bars either side (default n=3); swing high is the mirror. Pure function, hand-checked tests against a known series. |
| 6.2 | `AtrCalculator` — Average True Range (14) | True range = max(high−low, \|high−prevClose\|, \|low−prevClose\|). Used for stop buffers and as a sanity floor. Hand-checked test. |
| 6.3 | `PriceLevelService` — cluster pivots into support/resistance **zones** | Pivots within `atr × tolerance` of each other collapse into one zone. Each zone scores on **touches** (how many pivots), **recency** (bars since last touch) and **volume** at those bars. Returns zones sorted by distance from current price. |
| 6.4 | `LevelSuggestionService` — turn zones into a stop and a target | **Stop** = nearest support zone below price, minus a buffer (`0.5 × ATR` default) so noise at the level doesn't trigger it. **Target** = nearest resistance zone above price. Emits `LevelSuggestion(value, rationale, confidence, sourceZone)` or an explicit "no clean level" refusal. |
| 6.5 | Sanity guards — refuse rather than emit nonsense | No suggestion when: fewer than ~60 bars; no pivot below/above price in the window; stop would be >`maxStopPercent` (default 15%) from entry; resulting ratio is unreachable. Each refusal carries a plain reason. |
| 6.6 | **Journal provenance** — `levelSource` enum on `TradeJournalEntry` | HUMAN / SUGGESTED / EDITED (suggested then changed). Scorecard gains a breakdown so win rate and expectancy can be compared across the three. This is the feedback loop that makes Phase 6 evaluable. |
| 6.7 | `GET /api/levels/{symbol}` + prefill in `/plan` | Fields arrive pre-filled but visually marked **suggested**, with the rationale beside them and a one-click "clear and do it myself". |
| 6.8 | Level chart on the calculator page | Inline SVG: ~120 daily bars with the support and resistance zones shaded and the proposed stop/target drawn. No JS charting library — the data is already server-side. |
| 6.9 | Config in `ScanProperties`/new `LevelProperties` | `pivotStrength` (3), `atrPeriod` (14), `stopBufferAtrMultiple` (0.5), `zoneToleranceAtrMultiple` (0.5), `minTouches` (2), `maxStopPercent` (15), `lookbackBars` (250). All tunable without a rebuild. |

### Phase 6A — Backtest harness (build this first)

**Goal:** measure whether the suggested levels are any good, before trusting them. Every threshold in 6.9 is currently a guess; this turns each one into a measured choice.

**Why first:** a pivot detector always emits *something*. Without measurement there is no way to tell a well-chosen buffer from a badly-chosen one, and the suggestions would be adopted on faith. This is also the honest answer to "can AI make Phase 6 more accurate?" — no, but this can. It is deterministic arithmetic, no LLM involved.

#### The method
For each symbol and each historical bar `i` (walk-forward):
1. Compute levels using **only** `bars[0..i]`.
2. Take the suggested stop and target; entry = close of bar `i`.
3. Walk forward through `bars[i+1..]` and record which was touched first.
4. Stop after `timeStopBars` (default **15 trading days**, matching the management-rules panel) and record a TIMEOUT.

Outcomes: `TARGET_FIRST` · `STOP_FIRST` · `TIMEOUT` · `NO_SUGGESTION` (the refusal guards fired).

#### Correctness properties — these decide whether the numbers mean anything
| Risk | Handling |
|---|---|
| **Lookahead bias** | The killer. Levels must never see a bar at or after entry. Enforced by passing an explicit sublist, and asserted by a dedicated test that feeds a series whose future contains an obvious pivot and proves it is not used. |
| **Intrabar ambiguity** | If one daily bar's range spans both stop and target, daily data cannot say which came first. **Resolve as STOP_FIRST.** Assuming the favourable order is how backtests flatter themselves. |
| **Gap-through-stop** | If a bar opens below the stop, fill at the **open**, not the stop — the loss is larger than 1R. Record the true R. |
| **Survivorship bias** | Testing only on today's watchlist tests names that still exist and that you already like. State it in the report; it cannot be fixed with free data. |
| **Overfitting** | Split history: tune on the **older 70%**, validate on the **most recent 30%**, and report both. A parameter set that wins in-sample and loses out-of-sample is noise. |

#### Metrics — in **R**, not dollars
Position size varies, so results are reported in risk multiples: a target hit at 2.5× the risk distance is +2.5R, a clean stop is −1R, a gap-through may be −1.4R.
- Hit rate (target-first %), timeout %, no-suggestion %
- **Expectancy in R** — the headline number
- R distribution and worst observed R
- Median bars to resolution

#### Tasks
| # | Task |
|---|---|
| 6A.1 | `BacktestOutcome` / `BacktestTrade` / `BacktestReport` records (R-based metrics) |
| 6A.2 | `LevelBacktestService.replay(symbol, candles, params)` — walk-forward, no lookahead |
| 6A.3 | Conservative resolvers: intrabar ambiguity → stop, gap → fill at open |
| 6A.4 | **Lookahead-bias test** — the single most important test in the harness |
| 6A.5 | `ParameterSweep` — grid over pivotStrength × bufferAtrMultiple × minTouches, ranked by out-of-sample expectancy |
| 6A.6 | In-sample / out-of-sample split with both reported side by side |
| 6A.7 | `POST /api/backtest` + a results page: per-symbol and aggregate, with the caveats printed on the page, not buried in docs |
| 6A.8 | Wire the winning parameters into `LevelProperties` defaults — recording the date, sample size and out-of-sample expectancy that justified them |

#### Progress — 6A.1–6A.4 done (2026-07-30)
**304 tests green.** `LevelBacktestService.replay(symbol, bars, settings)` walks the series forward; `replayOne` is package-visible so a single entry can be asserted in isolation.

Two things the tests forced that were not in the original design:

1. **`INCOMPLETE` — censored observations.** The tail of every series produces entries whose walk-forward is cut short by the data ending, not by the time stop elapsing. Scoring those as `TIMEOUT` would understate both winners and losers. They are now counted separately and excluded from every metric. This surfaced when a trade resolved as TIMEOUT on a short series and STOP_FIRST on a longer one — *not* leakage, but it would have quietly biased every report.
2. **Unresolved trades keep their levels.** `NOT_TAKEABLE` and `INCOMPLETE` entries retain the stop and target that were computed, rather than discarding them. They were real engine output; throwing them away loses the audit trail and breaks the no-lookahead invariant assertion.

`NOT_TAKEABLE` covers a setup whose fill gapped to or through the stop before entry — no risk distance, so no trade, and inventing a loss there would be fiction.

Still pending: **6A.5–6A.8** (parameter sweep, in/out-of-sample split, endpoint + report page, adopting winners). Those need an API key and the three open decisions — entry rule is already a parameter (`NEXT_OPEN` default), universe and time stop are not.

#### Data budget
Candles are already cached per scanned symbol (250 bars ≈ 1 trading year), so a backtest over your watchlist costs **1 provider call per uncached symbol** and nothing for the rest. 250 bars yields roughly 200 walk-forward entries per symbol — thin for one name, reasonable across 20.

*Worth verifying before relying on it:* Twelve Data's `outputsize` is documented well above 250 (up to ~5000), so deeper history is likely a one-line config change at 1 call per symbol. Confirm with a real request before planning around it.

#### Success criteria — agree these before tuning
A parameter set is adopted only if, **out-of-sample**: expectancy is positive in R, the no-suggestion rate stays under ~40% (or the feature rarely helps), and it beats the naive baseline of *stop = entry − 2×ATR, target = entry + 2×risk*. **If nothing beats the naive baseline, that is the finding** — ship the baseline, or ship nothing, and keep setting levels by hand.


### Progress — Phase 6 complete, 6.1–6.9 (2026-07-30)
**290 tests green, coverage gate passing.** The endpoint shipped as `GET /api/marketdata/{symbol}/levels` rather than `/api/levels/{symbol}` — levels are market-derived, so they belong with the other market-data routes rather than in a fourth namespace.

Decisions taken on the four questions the plan left open:
- **Prefill, visibly marked.** Stop and target arrive filled in with a `suggested` tag, the rationale beside them, a chart, and a "Clear and set them myself" link that reloads with `suggestLevels=false`. That opt-out path is covered by its own test — it is the Phase 1–5 workflow and must keep working.
- **Structure-only stops.** No ATR fallback. When there is no clean shelf the engine refuses; an arbitrary distance wearing a formula is harder to argue with than a blank field, and therefore more dangerous.
- **Target = near edge of the nearest resistance**, not its centre, matching the existing "take profit into resistance" management rule.
- **Any change counts as EDITED**, down to a cent. Clearing a suggested level is also an edit.

Notes for 6A:
- `LevelSuggestionService.analyse(symbol, bars, price)` is the **pure** entry point — no I/O — so the backtest can hand it a historical sublist. `suggest(symbol)` is the thin fetching wrapper. Both are covered by no-lookahead tests.
- Every threshold in `LevelProperties` is still a **guess**. 6A exists to replace them with measured values; until then the UI says so in the caveat under the chart.

### Progress — 6.1 and 6.2 done (2026-07-30)
Both are pure functions on `List<Candle>`, no Spring wiring beyond `@Component`, 100% instruction and branch coverage, 18 hand-checked tests.

Decisions baked in, worth knowing before 6.3 builds on them:
- **Strict inequality both sides.** A flat double bottom at an identical low registers as *no* pivot rather than two. An ambiguous turn is not a turn — and it keeps 6.3's clustering from double-counting a single shelf.
- **The last `strength` bars are never confirmed.** A pivot needs bars to its right, so with strength 3 the newest 3 bars cannot produce one. This is not a gap to patch: a level only visible in hindsight was never tradeable. `SwingPointDetector.unconfirmedTailBars()` exists so the UI can say so.
- **`SwingPoint.barIndex` is retained** specifically so 6A.4 can prove no pivot at or after the entry bar was used.
- **Both detectors read only the list handed to them**, so passing a sublist ending at the entry bar is sufficient for no-lookahead. Asserted in both test classes.
- **ATR uses Wilder smoothing** (seed = SMA of first `period` true ranges, then `(prev × (n−1) + tr) ÷ n`), matching charting platforms — the same reasoning as `EmaCalculator`, so the numbers agree with what the user sees. First bar's true range is a bare high−low, as it has no previous close.
- **Null, never a guess, on short history** — ATR needs `period + 1` bars.

### Known limitations — state these in the UI, not just here
- **Support/resistance is not objective.** Different pivot strengths give different levels. The output will look authoritative and is not; it is one defensible reading among several.
- **Daily bars only.** No intraday structure, no volume profile, no trendlines, no moving-average support.
- **No context.** The detector cannot see an earnings gap, a sector move or a news catalyst that makes a level meaningless.
- **Over-fitting risk.** Tuning the parameters until levels look good on past charts is curve-fitting. Change them rarely and record why.
- **Skill cost.** Automating chart-reading during the paper phase removes the reps that phase exists to build. 6.6 is what keeps that trade-off measurable.

### Decisions needed before building
1. **Prefill or blank-with-hint?** Prefill the fields (faster, risks anchoring) vs. show suggestions beside empty fields (slower, keeps the human deciding first).
2. **Stop style:** structure-based (support − ATR buffer) only, or also offer a pure-ATR stop (`entry − 2×ATR`) for names with no clean structure?
3. **Target style:** nearest resistance only, or also a ratio-derived target (`entry + 2 × risk`) so a setup can be sized when overhead is clear?
4. **Provenance granularity:** is a 1-cent tweak to a suggested level EDITED or still SUGGESTED?

**Deliverable:** paste a Finviz list → tiered shortlist → click a Tier-1 row → calculator opens with entry, stop and target proposed, each with its reasoning and a chart → confirm, adjust or reject → size and journal. The human's job becomes *reviewing* a proposal rather than *constructing* one — which is a real change in the tool's character, deliberately made.

---

## Screener Sourcing (design decision)
The tool does NOT replicate a full-market screener. Free data APIs (Twelve Data, Finnhub) have no "scan every US stock above its 50-EMA" universe endpoint — they only query specific tickers you already name. Finviz maintains the whole-market database and runs the filters server-side, which the free APIs cannot.

**Chosen approach — Option 1 (manual Finviz → paste into tool):** Run the Finviz free screener (30 seconds), copy the resulting tickers, paste the whole list into the tool's scan textarea (Phase 4). The tool automates everything *after* the list: per-ticker data fetch, EMAs, filtering, tiering, and math. Don't rebuild what Finviz already does better.

**Documented future enhancements (not built now):**
- *Option 2 — Finviz Elite export:* paid (~$25/mo) export/API returns screen results as CSV; add a small `FinvizExportClient` to fetch the list automatically. Worth it only if manual copy-paste becomes real friction.
- *Option 3 — self-hosted universe scan:* store a fixed universe (e.g. S&P 500), batch-pull daily data into a local DB, run filters in Java. Real project; burns API budget; overkill for now.

## Cross-Cutting Tasks
- **Config & secrets:** API key via env var; `application-example.yml` committed, real config git-ignored.
- **Validation:** Bean Validation on `TradeSetup` (positive numbers, stop<entry<target).
- **Testing:** unit tests for calculator + EMA (deterministic); MockRestServiceServer for **both** `TwelveDataClient` and `FinnhubClient`. A JaCoCo gate fails the build below 80% line/branch/instruction.
- **README:** setup, how to get a **Twelve Data** key (primary) and a Finnhub key (secondary), run instructions, and a bold disclaimer: *educational tool, paper trading only, not financial advice, never auto-executes.*
- **Logging:** log every external call + rate-limit hits.

## Suggested Build Order
Phase 1 → 2 (usable calculator in a weekend) → 3 → 4 (the real time-saver) → 5. Ship each phase working before starting the next.

## Explicit Non-Goals (do not build)
- No order placement / broker integration.
- No "auto-buy" or signal generation.
- No leverage, options, or shorting logic.
- No real-time streaming (daily data is enough).