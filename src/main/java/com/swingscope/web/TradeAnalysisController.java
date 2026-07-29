package com.swingscope.web;

import com.swingscope.domain.TradeAnalysis;
import com.swingscope.domain.TradeSetup;
import com.swingscope.service.TradeCalculatorService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TradeAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(TradeAnalysisController.class);

    private final TradeCalculatorService calculator;

    public TradeAnalysisController(TradeCalculatorService calculator) {
        this.calculator = calculator;
    }

    @PostMapping("/analyze")
    public ResponseEntity<TradeAnalysis> analyze(@Valid @RequestBody TradeSetup setup) {
        log.info("POST /api/analyze received for ticker={}", setup.ticker());
        TradeAnalysis analysis = calculator.analyze(setup);
        log.info("POST /api/analyze responding 200 for ticker={} verdict={}",
                analysis.ticker(), analysis.pass() ? "PASS" : "FAIL");
        return ResponseEntity.ok(analysis);
    }
}
