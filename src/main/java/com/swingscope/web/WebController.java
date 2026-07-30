package com.swingscope.web;

import com.swingscope.config.TradingRules;
import com.swingscope.domain.journal.SetupType;
import com.swingscope.domain.journal.TradeJournalEntry;
import com.swingscope.domain.TradeAnalysis;
import com.swingscope.domain.TradeSetup;
import com.swingscope.service.TradeCalculatorService;
import com.swingscope.service.journal.TradeJournalService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

/** Serves the browser calculator. The REST API in {@link TradeAnalysisController} is unchanged. */
@Controller
public class WebController {

    private static final Logger log = LoggerFactory.getLogger(WebController.class);

    private final TradeCalculatorService calculator;
    private final TradingRules rules;
    private final TradeJournalService journal;

    public WebController(TradeCalculatorService calculator, TradingRules rules,
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
                                        RedirectAttributes redirect) {
        String symbol = ticker.trim().toUpperCase();

        // A PASS becomes a plan to act on; a FAIL becomes a record that the rules said no.
        TradeJournalEntry saved = journal.create(pass
                ? new TradeJournalEntry(symbol, setupType, entry, stop, target, ratio, shares, riskAmount)
                : TradeJournalEntry.rejected(symbol, setupType, entry, stop, target, ratio,
                        shares, riskAmount, reason));

        log.info("Calculator result for {} journalled as {} entry #{}",
                symbol, saved.getStatus(), saved.getId());
        redirect.addFlashAttribute("flash", pass
                ? "Planned trade #%d logged for %s. Update it when it fills."
                        .formatted(saved.getId(), symbol)
                : "Saved #%d for %s as rejected — it stays out of your win rate and expectancy."
                        .formatted(saved.getId(), symbol));
        return "redirect:/journal/" + saved.getId();
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
}
