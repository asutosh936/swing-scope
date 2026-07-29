package com.swingscope.web;

import com.swingscope.config.RequestLoggingFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class TradeAnalysisControllerTest {

    private final MockMvc mockMvc;

    @Autowired
    TradeAnalysisControllerTest(WebApplicationContext context) {
        // Full filter chain so RequestLoggingFilter runs on every request under test.
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new RequestLoggingFilter())
                .build();
    }

    @Test
    void analyzeReturnsAnalysisForAValidSetup() throws Exception {
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticker": "VZ",
                                  "entry": 40.00,
                                  "stop": 39.00,
                                  "target": 43.60,
                                  "accountSize": 500,
                                  "riskPct": 1.0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("VZ"))
                .andExpect(jsonPath("$.ratio").value(3.60))
                .andExpect(jsonPath("$.wholeShares").value(5))
                .andExpect(jsonPath("$.totalRisk").value(5.00))
                .andExpect(jsonPath("$.pass").value(true))
                .andExpect(jsonPath("$.reason").value("PASS"));
    }

    @Test
    void analyzeReturnsFailVerdictWithoutAnErrorStatus() throws Exception {
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticker": "CI",
                                  "entry": 20.00,
                                  "stop": 19.00,
                                  "target": 21.87,
                                  "accountSize": 500,
                                  "riskPct": 1.0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pass").value(false))
                .andExpect(jsonPath("$.reason").value("ratio 1.87 < 2.0"));
    }

    @Test
    void analyzeRejectsANegativeEntryPrice() throws Exception {
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticker": "VZ",
                                  "entry": -40.00,
                                  "stop": 39.00,
                                  "target": 43.60,
                                  "accountSize": 500,
                                  "riskPct": 1.0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid trade setup"))
                .andExpect(jsonPath("$.fieldErrors.entry").value("must be greater than 0"));
    }

    @Test
    void analyzeRejectsABlankTickerAndMissingNumbers() throws Exception {
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticker": "  ",
                                  "entry": 40.00,
                                  "stop": 39.00,
                                  "target": 43.60
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.ticker").exists())
                .andExpect(jsonPath("$.fieldErrors.accountSize").exists())
                .andExpect(jsonPath("$.fieldErrors.riskPct").exists());
    }

    @Test
    void analyzeRejectsAMalformedBody() throws Exception {
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json at all "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("malformed request body"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }
}
