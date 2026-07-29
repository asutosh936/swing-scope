package com.swingscope.web;

import com.swingscope.config.TradingRules;
import com.swingscope.domain.TradeAnalysis;
import com.swingscope.domain.TradeSetup;
import com.swingscope.service.TradeCalculatorService;
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

import java.math.BigDecimal;

/** Serves the browser calculator. The REST API in {@link TradeAnalysisController} is unchanged. */
@Controller
public class WebController {

    private static final Logger log = LoggerFactory.getLogger(WebController.class);

    private final TradeCalculatorService calculator;
    private final TradingRules rules;

    public WebController(TradeCalculatorService calculator, TradingRules rules) {
        this.calculator = calculator;
        this.rules = rules;
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
