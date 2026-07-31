package com.swingscope.web;

import com.swingscope.domain.TradeAnalysis;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@ActiveProfiles("test")
class CalculatorControllerTest {

    private final MockMvc mockMvc;

    @Autowired
    CalculatorControllerTest(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void formPageRendersWithTheConfiguredDefaults() throws Exception {
        MvcResult result = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("calculator"))
                .andExpect(model().attributeExists("form"))
                .andExpect(model().attributeDoesNotExist("analysis"))
                .andReturn();

        TradeSetupForm form = (TradeSetupForm) result.getModelAndView().getModel().get("form");
        assertThat(form.getAccountSize()).isEqualByComparingTo("500");
        assertThat(form.getRiskAmount()).isEqualByComparingTo("5.00");
        assertThat(form.getTicker()).isNull();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Size a trade").contains("Not financial advice");
    }

    @Test
    void submittingAPassingSetupRendersTheResultAndTheManagementRules() throws Exception {
        MvcResult result = mockMvc.perform(post("/analyze")
                        .param("ticker", "vz")
                        .param("entry", "40.00")
                        .param("stop", "39.00")
                        .param("target", "43.60")
                        .param("accountSize", "500")
                        .param("riskAmount", "5.00"))
                .andExpect(status().isOk())
                .andExpect(view().name("calculator"))
                .andExpect(model().attributeExists("analysis"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Manage it by the rules")))
                .andReturn();

        TradeAnalysis analysis = (TradeAnalysis) result.getModelAndView().getModel().get("analysis");
        assertThat(analysis.ticker()).isEqualTo("VZ");   // lowercased input is normalised
        assertThat(analysis.pass()).isTrue();
        assertThat(analysis.ratio()).isEqualByComparingTo("3.60");

        String html = result.getResponse().getContentAsString();
        assertThat(html)
                .contains("ratio-ok")
                .doesNotContain("ratio-bad")
                .doesNotContain("class=\"reason\"");   // badge already says PASS
    }

    @Test
    void aRuleFailureRendersAFailVerdictRatherThanAnError() throws Exception {
        MvcResult result = mockMvc.perform(post("/analyze")
                        .param("ticker", "CI")
                        .param("entry", "20.00")
                        .param("stop", "19.00")
                        .param("target", "21.87")
                        .param("accountSize", "500")
                        .param("riskAmount", "5.00"))
                .andExpect(status().isOk())
                .andExpect(view().name("calculator"))
                .andReturn();

        TradeAnalysis analysis = (TradeAnalysis) result.getModelAndView().getModel().get("analysis");
        assertThat(analysis.pass()).isFalse();

        String html = result.getResponse().getContentAsString();
        assertThat(html)
                .contains("ratio 1.87 &lt; 2.0")
                .contains("ratio-bad")
                .doesNotContain("Manage it by the rules");   // no rules panel on a FAIL
    }

    @Test
    void aStopAboveEntryComesBackAsAFailWithTheReasonShown() throws Exception {
        mockMvc.perform(post("/analyze")
                        .param("ticker", "XYZ")
                        .param("entry", "40.00")
                        .param("stop", "41.00")
                        .param("target", "45.00")
                        .param("accountSize", "500")
                        .param("riskAmount", "5.00"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("stop must be below entry")));
    }

    @Test
    void invalidInputRedisplaysTheFormWithFieldErrorsAndNoAnalysis() throws Exception {
        MvcResult result = mockMvc.perform(post("/analyze")
                        .param("ticker", "   ")
                        .param("entry", "-40.00")
                        .param("stop", "")
                        .param("target", "43.60")
                        .param("accountSize", "500")
                        .param("riskAmount", "5.00"))
                .andExpect(status().isOk())
                .andExpect(view().name("calculator"))
                .andExpect(model().attributeDoesNotExist("analysis"))
                .andExpect(model().attributeHasFieldErrors("form", "ticker", "entry", "stop"))
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html)
                .contains("ticker is required")
                .contains("entry must be greater than 0")
                .contains("stop is required")
                .contains("class=\"field-error\"");
    }

    @Test
    void unparseableNumbersAreReportedAsFieldErrorsRatherThanA500() throws Exception {
        mockMvc.perform(post("/analyze")
                        .param("ticker", "VZ")
                        .param("entry", "not-a-number")
                        .param("stop", "39.00")
                        .param("target", "43.60")
                        .param("accountSize", "500")
                        .param("riskAmount", "5.00"))
                .andExpect(status().isOk())
                .andExpect(view().name("calculator"))
                .andExpect(model().attributeHasFieldErrors("form", "entry"))
                .andExpect(model().attributeDoesNotExist("analysis"));
    }
}
