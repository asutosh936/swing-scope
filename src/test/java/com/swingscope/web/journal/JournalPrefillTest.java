package com.swingscope.web.journal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** Task 8.8 — a scan row hands its numbers to the journal form rather than making you retype them. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class JournalPrefillTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("the create form fills itself from a scan row's numbers")
    void createFormAcceptsPrefill() throws Exception {
        String html = mockMvc.perform(get("/journal/new")
                        .param("ticker", "nvda")
                        .param("entry", "206.64")
                        .param("stop", "190.85")
                        .param("target", "212.19")
                        .param("ratio", "2.10")
                        .param("shares", "3"))
                .andExpect(status().isOk())
                .andExpect(view().name("journal-form"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("value=\"NVDA\"")      // normalised, as everywhere else
                .contains("206.64")
                .contains("190.85")
                .contains("212.19")
                .contains("value=\"3\"");
    }

    @Test
    @DisplayName("with no parameters it is still a blank form — the prefill is additive")
    void createFormWorksWithoutParameters() throws Exception {
        mockMvc.perform(get("/journal/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("journal-form"));
    }
}
