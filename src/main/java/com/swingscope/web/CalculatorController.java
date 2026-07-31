package com.swingscope.web;

import com.swingscope.config.OpenApiConfig;
import com.swingscope.config.TradingRules;
import com.swingscope.domain.journal.LevelSource;
import com.swingscope.domain.journal.SetupType;
import com.swingscope.domain.journal.TradeJournalEntry;
import com.swingscope.domain.TradeAnalysis;
import com.swingscope.domain.TradeSetup;
import com.swingscope.service.TradeCalculatorService;
import com.swingscope.service.journal.TradeJournalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

/**
 * The calculator feature: the browser form, the journal hand-off, and the one JSON endpoint worth
 * scripting.
 */
@Controller
public class CalculatorController {

    private static final Logger log = LoggerFactory.getLogger(CalculatorController.class);

    private final TradeCalculatorService calculator;
    private final TradingRules rules;
    private final TradeJournalService journal;

    public CalculatorController(TradeCalculatorService calculator, TradingRules rules,
                         TradeJournalService journal) {
        this.calculator = calculator;
        this.rules = rules;
        this.journal = journal;
    }

    /** Blank strings from empty form fields become nulls, so @NotNull reports them properly. */
    @InitBinder
    void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
    }

    /** Available to every view, so the template can compare the ratio against the configured minimum. */
    @ModelAttribute("minRiskReward")
    BigDecimal minRiskReward() {
        return rules.minRiskReward();
    }

    /** For the "journal this trade" setup picker on a PASS result. */
    @ModelAttribute("setupTypes")
    SetupType[] setupTypes() {
        return SetupType.values();
    }

    /**
     * Task 5.8 — one click from a PASS verdict to a PLANNED journal entry, so planning and
     * journalling are a single step. Phase 4's "Plan this trade" will land here too.
     */
    @PostMapping("/journal/from-calculator")
    public String journalFromCalculator(@RequestParam String ticker,
                                        @RequestParam BigDecimal entry,
                                        @RequestParam BigDecimal stop,
                                        @RequestParam BigDecimal target,
                                        @RequestParam(required = false) BigDecimal ratio,
                                        @RequestParam int shares,
                                        @RequestParam(required = false) BigDecimal riskAmount,
                                        @RequestParam(required = false) SetupType setupType,
                                        @RequestParam(defaultValue = "true") boolean pass,
                                        @RequestParam(required = false) String reason,
                                        @RequestParam(required = false) BigDecimal suggestedStop,
                                        @RequestParam(required = false) BigDecimal suggestedTarget,
                                        RedirectAttributes redirect) {
        String symbol = ticker.trim().toUpperCase();
        LevelSource levelSource = provenanceOf(stop, target, suggestedStop, suggestedTarget);

        // A PASS becomes a plan to act on; a FAIL becomes a record that the rules said no.
        TradeJournalEntry saved = journal.create(pass
                ? new TradeJournalEntry(symbol, setupType, entry, stop, target, ratio, shares, riskAmount)
                : TradeJournalEntry.rejected(symbol, setupType, entry, stop, target, ratio,
                        shares, riskAmount, reason));

        saved.setLevelSource(levelSource);
        log.info("Calculator result for {} journalled as {} entry #{} (levels: {})",
                symbol, saved.getStatus(), saved.getId(), levelSource);
        redirect.addFlashAttribute("flash", pass
                ? "Planned trade #%d logged for %s. Update it when it fills."
                        .formatted(saved.getId(), symbol)
                : "Saved #%d for %s as rejected — it stays out of your win rate and expectancy."
                        .formatted(saved.getId(), symbol));
        return "redirect:/journal/" + saved.getId();
    }

    /**
     * Phase 6.6 — where did the levels come from?
     *
     * <p>Any difference from what was proposed counts as EDITED, down to a cent: adjusting a level
     * is a decision, and lumping small tweaks in with untouched suggestions would blur exactly the
     * comparison this field exists to make.
     */
    static LevelSource provenanceOf(BigDecimal stop, BigDecimal target,
                                    BigDecimal suggestedStop, BigDecimal suggestedTarget) {
        if (suggestedStop == null && suggestedTarget == null) {
            return LevelSource.HUMAN;
        }
        boolean stopUntouched = matches(stop, suggestedStop);
        boolean targetUntouched = matches(target, suggestedTarget);
        return stopUntouched && targetUntouched ? LevelSource.SUGGESTED : LevelSource.EDITED;
    }

    /** A suggestion that was never made cannot have been altered. */
    private static boolean matches(BigDecimal actual, BigDecimal suggested) {
        if (suggested == null) {
            return true;
        }
        return actual != null && actual.compareTo(suggested) == 0;
    }

    @GetMapping("/")
    public String showForm(Model model) {
        log.info("Rendering calculator form with defaults account={} riskAmount={}",
                rules.defaultAccountSize(), rules.defaultRiskAmount());
        TradeSetupForm form = new TradeSetupForm();
        form.setAccountSize(rules.defaultAccountSize());
        form.setRiskAmount(rules.defaultRiskAmount());
        model.addAttribute("form", form);
        return "calculator";
    }

    @PostMapping("/analyze")
    public String analyze(@Valid @ModelAttribute("form") TradeSetupForm form,
                          BindingResult binding,
                          Model model) {
        if (binding.hasErrors()) {
            log.warn("Calculator form rejected: {} field error(s) — {}",
                    binding.getErrorCount(), binding.getFieldErrors().stream()
                            .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList());
            return "calculator";
        }

        TradeSetup setup = form.toSetup();
        TradeAnalysis analysis = calculator.analyze(setup);
        log.info("Calculator form analyzed {} -> verdict={}",
                analysis.ticker(), analysis.pass() ? "PASS" : "FAIL");
        model.addAttribute("analysis", analysis);
        return "calculator";
    }

    // ------------------------------------------------------------------- JSON, for scripting

    /**
     * The sizing math over HTTP. A setup that breaks a rule still returns 200 — the verdict is in
     * {@code pass} and {@code reason}; only structurally invalid input is a 400.
     */
    @Operation(
            summary = "Size a trade and check it against the rules",
            description = """
                    Pure arithmetic on the numbers you supply — no market data is fetched and nothing                     is stored.

                    Computes risk and reward per share, the reward:risk ratio, and how many whole                     shares fit inside your dollar risk budget, capped by what the account can afford.                     Share counts always round **down**, so realized risk lands at or under budget.

                    A setup that breaks a rule is **not an error**: the response is 200 with                     `pass: false` and a `reason` such as `ratio 1.87 < 2.0`. The tool may refuse to                     size a trade; it never tells you to take one.
                    """,
            tags = OpenApiConfig.TAG_CALCULATOR)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Analysis complete. Check `pass` for the verdict — a FAIL is still a 200.",
                    content = @Content(schema = @Schema(implementation = TradeAnalysis.class),
                            examples = {
                                    @ExampleObject(name = "PASS", value = """
                                            {"ticker":"VZ","riskPerShare":1.00,"rewardPerShare":3.60,
                                             "ratio":3.60,"idealShares":5.0000,"wholeShares":5,
                                             "totalRisk":5.00,"positionCost":200.00,"cashLeft":300.00,
                                             "pass":true,"reason":"PASS"}"""),
                                    @ExampleObject(name = "FAIL — ratio too low", value = """
                                            {"ticker":"CI","riskPerShare":1.00,"rewardPerShare":1.87,
                                             "ratio":1.87,"idealShares":5.0000,"wholeShares":5,
                                             "totalRisk":5.00,"positionCost":100.00,"cashLeft":400.00,
                                             "pass":false,"reason":"ratio 1.87 < 2.0"}""")
                            })),
            @ApiResponse(responseCode = "400",
                    description = "Structurally invalid input — a missing, zero or negative number, "
                            + "a blank ticker, or malformed JSON. Returns a `fieldErrors` map.",
                    content = @Content(examples = @ExampleObject(value = """
                            {"timestamp":"2026-07-29T15:14:35Z","status":400,
                             "message":"invalid trade setup",
                             "fieldErrors":{"entry":"must be greater than 0"}}""")))
    })
    @PostMapping("/api/analyze")
    @ResponseBody
    public TradeAnalysis analyzeApi(@Valid @RequestBody TradeSetup setup) {
        log.info("POST /api/analyze for ticker={}", setup.ticker());
        TradeAnalysis analysis = calculator.analyze(setup);
        log.info("POST /api/analyze -> {}", analysis.pass() ? "PASS" : "FAIL");
        return analysis;
    }
}
