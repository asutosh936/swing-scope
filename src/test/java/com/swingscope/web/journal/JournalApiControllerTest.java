package com.swingscope.web.journal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JournalApiControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockMvc mockMvc;

    @Autowired
    JournalApiControllerTest(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static final String PLAN = """
            {"ticker":"vz","setupType":"BREAKOUT","entry":40.00,"stop":39.00,"target":43.60,
             "ratio":3.60,"shares":5,"riskAmount":5.00}
            """;

    private long createPlanned() throws Exception {
        String body = mockMvc.perform(post("/api/journal")
                        .contentType(MediaType.APPLICATION_JSON).content(PLAN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = MAPPER.readTree(body);
        return node.get("id").asLong();
    }

    @Test
    void createsAnEntryAndNormalisesTheTicker() throws Exception {
        mockMvc.perform(post("/api/journal").contentType(MediaType.APPLICATION_JSON).content(PLAN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticker").value("VZ"))
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.realizedPnl").doesNotExist());
    }

    @Test
    void rejectsAnEntryWithoutATicker() throws Exception {
        mockMvc.perform(post("/api/journal").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticker":"","entry":40.00,"stop":39.00,"target":43.60,"shares":5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.ticker").exists());
    }

    @Test
    void listsAndFetchesEntries() throws Exception {
        long id = createPlanned();

        mockMvc.perform(get("/api/journal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("VZ"));

        mockMvc.perform(get("/api/journal/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void unknownIdIs404() throws Exception {
        mockMvc.perform(get("/api/journal/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("no journal entry with id 99999"));
    }

    @Test
    void walksTheFullLifecycleToAWin() throws Exception {
        long id = createPlanned();

        mockMvc.perform(post("/api/journal/" + id + "/fill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fillPrice\":40.05,\"actualShares\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.fillPrice").value(40.05));

        mockMvc.perform(post("/api/journal/" + id + "/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"exitPrice":43.60,"lessonText":"waited for the trigger candle",
                                 "rulesFollowed":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED_WIN"))
                .andExpect(jsonPath("$.realizedPnl").value(17.75))
                .andExpect(jsonPath("$.lessonText").value("waited for the trigger candle"));
    }

    @Test
    void closingAnUnfilledTradeIs409() throws Exception {
        long id = createPlanned();

        mockMvc.perform(post("/api/journal/" + id + "/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exitPrice\":43.60,\"lessonText\":\"x\",\"rulesFollowed\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("cannot move a trade from PLANNED")));
    }

    @Test
    void closingWithoutALessonIsRejectedByValidation() throws Exception {
        long id = createPlanned();
        mockMvc.perform(post("/api/journal/" + id + "/fill")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fillPrice\":40.00}"));

        mockMvc.perform(post("/api/journal/" + id + "/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exitPrice\":43.60,\"lessonText\":\"\",\"rulesFollowed\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.lessonText").exists());
    }

    @Test
    void noFillTransitions() throws Exception {
        long id = createPlanned();

        mockMvc.perform(post("/api/journal/" + id + "/no-fill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_FILL"));
    }

    @Test
    void statsReportTheScorecard() throws Exception {
        long id = createPlanned();
        mockMvc.perform(post("/api/journal/" + id + "/fill")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fillPrice\":40.00}"));
        mockMvc.perform(post("/api/journal/" + id + "/close")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"exitPrice\":44.00,\"lessonText\":\"ok\",\"rulesFollowed\":true}"));

        mockMvc.perform(get("/api/journal/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedCount").value(1))
                .andExpect(jsonPath("$.wins").value(1))
                .andExpect(jsonPath("$.winRate").value(100.0))
                .andExpect(jsonPath("$.netPnl").value(20.00))
                .andExpect(jsonPath("$.graduationTarget").value(25))
                .andExpect(jsonPath("$.graduationMet").value(false));
    }

    @Test
    void deleteRemovesTheEntry() throws Exception {
        long id = createPlanned();

        mockMvc.perform(delete("/api/journal/" + id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/journal/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void updateReplacesThePlan() throws Exception {
        long id = createPlanned();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/journal/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticker":"VZ","setupType":"PULLBACK","entry":41.00,"stop":40.00,
                                 "target":45.00,"ratio":4.00,"shares":4,"riskAmount":4.00}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupType").value("PULLBACK"))
                .andExpect(jsonPath("$.shares").value(4));
    }

    @Test
    void createsARejectedSetupThatCountsNowhere() throws Exception {
        mockMvc.perform(post("/api/journal/rejected").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticker":"ci","setupType":"BREAKOUT","entry":20.00,"stop":19.00,
                                 "target":21.87,"ratio":1.87,"shares":5,"riskAmount":5.00,
                                 "reason":"ratio 1.87 < 2.0"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticker").value("CI"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.lessonText").value("Rejected by the rules: ratio 1.87 < 2.0"));

        mockMvc.perform(get("/api/journal/stats"))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.closedCount").value(0))
                .andExpect(jsonPath("$.openTrades").value(0));
    }

    @Test
    void aRejectedSetupCannotBeFilled() throws Exception {
        String body = mockMvc.perform(post("/api/journal/rejected")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticker\":\"CI\",\"entry\":20.00,\"stop\":19.00,"
                                + "\"target\":21.87,\"shares\":5,\"reason\":\"no\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = MAPPER.readTree(body).get("id").asLong();

        mockMvc.perform(post("/api/journal/" + id + "/fill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fillPrice\":20.00}"))
                .andExpect(status().isConflict());
    }

    @Test
    void statsAreEmptyWhenNothingIsLogged() throws Exception {
        String body = mockMvc.perform(get("/api/journal/stats"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(MAPPER.readTree(body).get("totalEntries").asLong()).isZero();
    }
}
