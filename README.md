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
| 2 | Thymeleaf calculator UI | ⬜ Not started |
| 3 | Market data (Twelve Data primary, Finnhub secondary) | ⬜ Not started |
| 4 | Auto-tiering & watchlist scan | ⬜ Not started |
| 5 | Trade journal UI + optional Spring AI | ⬜ Not started |

Full task breakdown: [swing-trade-assistant-implementation-plan.md](swing-trade-assistant-implementation-plan.md).

---

## Tech stack

- Java 17 (enforced via `maven.compiler.release=17`; builds fine on a newer JDK)
- Spring Boot 3.3.5 — Spring Web, Bean Validation
- Maven
- JUnit 5 + AssertJ + MockMvc, JaCoCo with an **80% line/branch/instruction gate**

Phases 3–5 add Thymeleaf, Spring Data JPA (H2 file mode), and optionally Spring AI.

## Prerequisites

- JDK 17 or newer (developed against JDK 21, compiled to 17 bytecode)
- Maven 3.9+

No API key is needed for Phase 1 — the calculator makes no external calls.

---

## Running it

Start the app on port 8080:

```bash
mvn spring-boot:run
```

Analyze a setup:

```bash
curl -s -X POST http://localhost:8080/api/analyze -H 'Content-Type: application/json' -d '{"ticker":"VZ","entry":40.00,"stop":39.00,"target":43.60,"accountSize":500,"riskPct":1.0}'
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
curl -s -X POST http://localhost:8080/api/analyze -H 'Content-Type: application/json' -d '{"ticker":"CI","entry":20.00,"stop":19.00,"target":21.87,"accountSize":500,"riskPct":1.0}'
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

## API

### `POST /api/analyze`

Request — `riskPct` is **percent form**: `1.0` means 1% of the account.

| Field | Type | Rule |
|---|---|---|
| `ticker` | string | not blank |
| `entry` | decimal | > 0 |
| `stop` | decimal | > 0, and below `entry` |
| `target` | decimal | > 0, and above `entry` |
| `accountSize` | decimal | > 0 |
| `riskPct` | decimal | > 0, percent form (`1.0` = 1%) |

Response fields: `riskPerShare`, `rewardPerShare`, `ratio`, `idealShares` (unrounded),
`wholeShares` (tradeable), `totalRisk`, `positionCost`, `cashLeft`, `pass`, `reason`.

---

## How the sizing works

```
riskPerShare    = entry − stop                      (guard: stop < entry)
rewardPerShare  = target − entry                    (guard: target > entry)
ratio           = rewardPerShare ÷ riskPerShare     (scale 2, HALF_UP)
riskBudget      = accountSize × riskPct ÷ 100       ($500 @ 1% = $5)
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
    default-risk-pct: 1.0       # percent form
```

Override at runtime, e.g. `--trading.rules.min-risk-reward=3.0`.

From Phase 3 on, provider API keys (Twelve Data, optionally Finnhub) come from **environment
variables only** — never committed. `application-local.yml`, `application-secrets.yml`, and `.env`
are git-ignored.

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
  config/    TradingRules (configurable domain rules), RequestLoggingFilter
  domain/    TradeSetup, TradeAnalysis
  service/   TradeCalculatorService — all the math
  web/       TradeAnalysisController, ApiExceptionHandler
```

## Non-goals

No order placement or broker integration. No auto-buy or signal generation. No leverage, options, or
shorting. No real-time streaming — daily data is enough.
