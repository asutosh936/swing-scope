package com.swingscope.web.journal;

import com.swingscope.domain.journal.SetupType;
import com.swingscope.domain.journal.TradeJournalEntry;
import com.swingscope.service.journal.TradeJournalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The journal's JSON surface is <strong>read-only</strong>. Writes go through the UI, so there is a
 * single write path rather than two implementations to keep in step.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JournalApiTest {

    private final MockMvc mockMvc;

    @Autowired
    private TradeJournalService journal;

    @Autowired
    JournalApiTest(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private TradeJournalEntry closedWin() {
        TradeJournalEntry e = journal.create(new TradeJournalEntry("VZ", SetupType.BREAKOUT,
                new BigDecimal("40.00"), new BigDecimal("39.00"), new BigDecimal("43.60"),
                new BigDecimal("3.60"), 5, new BigDecimal("5.00")));
        journal.markFilled(e.getId(), new BigDecimal("40.00"), 5);
        return journal.close(e.getId(), new BigDecimal("44.00"), "let it run", true);
    }

    @Test
    void listsEntries() throws Exception {
        closedWin();

        mockMvc.perform(get("/api/journal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("VZ"))
                .andExpect(jsonPath("$[0].status").value("CLOSED_WIN"))
                .andExpect(jsonPath("$[0].realizedPnl").value(20.00));
    }

    @Test
    void fetchesOneEntry() throws Exception {
        long id = closedWin().getId();

        mockMvc.perform(get("/api/journal/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.lessonText").value("let it run"));
    }

    @Test
    void unknownIdIs404() throws Exception {
        mockMvc.perform(get("/api/journal/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("no journal entry with id 99999"));
    }

    @Test
    void reportsTheScorecard() throws Exception {
        closedWin();

        mockMvc.perform(get("/api/journal/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedCount").value(1))
                .andExpect(jsonPath("$.wins").value(1))
                .andExpect(jsonPath("$.winRate").value(100.0))
                .andExpect(jsonPath("$.netPnl").value(20.00))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.graduationTarget").value(25))
                .andExpect(jsonPath("$.graduationMet").value(false));
    }

    @Test
    void statsWorkOnAnEmptyJournal() throws Exception {
        mockMvc.perform(get("/api/journal/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEntries").value(0))
                .andExpect(jsonPath("$.netPnl").value(0));
    }

    @Test
    @DisplayName("the write endpoints are deliberately gone — the UI is the single write path")
    void writeEndpointsAreNotExposed() throws Exception {
        long id = closedWin().getId();

        // 404 or 405 depending on whether a same-path GET mapping exists; either way, gone.
        mockMvc.perform(post("/api/journal")).andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/journal/rejected")).andExpect(status().is4xxClientError());
        mockMvc.perform(put("/api/journal/" + id)).andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/journal/" + id + "/fill")).andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/journal/" + id + "/close")).andExpect(status().is4xxClientError());
        mockMvc.perform(delete("/api/journal/" + id)).andExpect(status().is4xxClientError());
    }
}
