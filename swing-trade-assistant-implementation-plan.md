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
| **4 — Auto-Tiering & Scan** | 4.1 | `TierService.tier(tickers)` with rule engine | ☐ |
| | 4.2 | `Watchlist` entity + CRUD + UI list | ☐ |
| | 4.3 | `scan.html` (paste tickers / scan watchlist → tiered table) | ☐ |
| | 4.4 | "Plan this trade" prefills calculator | ☐ |
| | 4.5 | Rate-limit-aware batching | ☐ |
| **5 — Journal + UI + AI** | 5.1 | `TradeJournalEntry` entity + repository | ☐ |
| | 5.2 | Journal CRUD REST endpoints | ☐ |
| | 5.3 | **Journal UI: list view = running scorecard** | ☐ |
| | 5.4 | **Journal UI: detail view (plan + execution + outcome)** | ☐ |
| | 5.5 | **Journal UI: create/edit form + status transitions** | ☐ |
| | 5.6 | Auto-computed P&L + win-rate/expectancy summary | ☐ |
| | 5.7 | Graduation tracker (progress to 25–30, positive total) | ☐ |
| | 5.8 | "Plan this trade" → auto-creates a PLANNED journal entry | ☐ |
| | 5.9 | (optional) Spring AI news-summary endpoint | ☐ |
| | 5.10 | (optional) Spring AI journal-narrative endpoint | ☐ |
| **Cross-cutting** | X.1 | Config & secrets (env var, example yml, git-ignore) | ◐ |
| | X.2 | Bean Validation on `TradeSetup` | ◐ |
| | X.3 | Tests: calculator + EMA + provider clients (MockRestServiceServer) | ◐ |
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