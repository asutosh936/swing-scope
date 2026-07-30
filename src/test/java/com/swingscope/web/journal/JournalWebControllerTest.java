package com.swingscope.web.journal;

import com.swingscope.domain.journal.SetupType;
import com.swingscope.domain.journal.TradeJournalEntry;
import com.swingscope.domain.journal.TradeStatus;
import com.swingscope.service.journal.TradeJournalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JournalWebControllerTest {

    private final MockMvc mockMvc;

    @Autowired
    private TradeJournalService journal;

    @Autowired
    JournalWebControllerTest(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private TradeJournalEntry plan() {
        return journal.create(new TradeJournalEntry("VZ", SetupType.BREAKOUT,
                new BigDecimal("40.00"), new BigDecimal("39.00"), new BigDecimal("43.60"),
                new BigDecimal("3.60"), 5, new BigDecimal("5.00")));
    }

    @Test
    void emptyJournalExplainsWhatToDo() throws Exception {
        MvcResult result = mockMvc.perform(get("/journal"))
                .andExpect(status().isOk())
                .andExpect(view().name("journal"))
                .andExpect(model().attributeExists("stats", "entries"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("No trades logged yet")
                .contains("Graduation to real money")
                .contains("0 / 25");
    }

    @Test
    void listShowsTheScorecardAndTheTrade() throws Exception {
        plan();

        MvcResult result = mockMvc.perform(get("/journal"))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("VZ").contains("Planned").contains("pill-planned");
    }

    @Test
    void detailShowsAllThreeLifecycleBands() throws Exception {
        TradeJournalEntry entry = plan();

        MvcResult result = mockMvc.perform(get("/journal/" + entry.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("journal-detail"))
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html)
                .contains("The plan").contains("Execution").contains("Outcome")
                .contains("Did it fill?");          // PLANNED offers the fill transition
    }

    @Test
    void detailOfAFilledTradeOffersTheCloseForm() throws Exception {
        TradeJournalEntry entry = plan();
        journal.markFilled(entry.getId(), new BigDecimal("40.00"), 5);

        MvcResult result = mockMvc.perform(get("/journal/" + entry.getId())).andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("Close this trade")
                .contains("One-sentence lesson")
                .doesNotContain("Did it fill?");
    }

    @Test
    void createFormRendersWithTheSetupDropdown() throws Exception {
        MvcResult result = mockMvc.perform(get("/journal/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("journal-form"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("Breakout").contains("Pullback").contains("Reversal");
    }

    @Test
    void submittingTheFormCreatesAnEntryAndRedirectsToIt() throws Exception {
        mockMvc.perform(post("/journal")
                        .param("ticker", "carr")
                        .param("setupType", "PULLBACK")
                        .param("entry", "15.50")
                        .param("stop", "14.75")
                        .param("target", "18.50")
                        .param("ratio", "4.00")
                        .param("shares", "6")
                        .param("riskAmount", "4.50"))
                .andExpect(status().is3xxRedirection());

        assertThat(journal.findAll()).first().satisfies(e -> {
            assertThat(e.getTicker()).isEqualTo("CARR");
            assertThat(e.getSetupType()).isEqualTo(SetupType.PULLBACK);
            assertThat(e.getStatus()).isEqualTo(TradeStatus.PLANNED);
        });
    }

    @Test
    void anInvalidFormRedisplaysWithErrors() throws Exception {
        MvcResult result = mockMvc.perform(post("/journal")
                        .param("ticker", "")
                        .param("entry", "-1")
                        .param("stop", "39.00")
                        .param("target", "43.60")
                        .param("shares", "5"))
                .andExpect(status().isOk())
                .andExpect(view().name("journal-form"))
                .andExpect(model().attributeHasFieldErrors("form", "ticker", "entry"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("ticker is required");
    }

    @Test
    void fillAndCloseThroughTheUi() throws Exception {
        TradeJournalEntry entry = plan();
        long id = entry.getId();

        mockMvc.perform(post("/journal/" + id + "/fill")
                        .param("fillPrice", "40.00")
                        .param("actualShares", "5"))
                .andExpect(redirectedUrl("/journal/" + id));

        mockMvc.perform(post("/journal/" + id + "/close")
                        .param("exitPrice", "44.00")
                        .param("lessonText", "let it run to target")
                        .param("rulesFollowed", "true"))
                .andExpect(redirectedUrl("/journal/" + id));

        TradeJournalEntry closed = journal.findById(id);
        assertThat(closed.getStatus()).isEqualTo(TradeStatus.CLOSED_WIN);
        assertThat(closed.getRealizedPnl()).isEqualByComparingTo("20.00");
    }

    @Test
    void anIllegalTransitionComesBackAsAFlashErrorNotAnException() throws Exception {
        TradeJournalEntry entry = plan();

        mockMvc.perform(post("/journal/" + entry.getId() + "/close")
                        .param("exitPrice", "44.00")
                        .param("lessonText", "x")
                        .param("rulesFollowed", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attributeExists("error"));

        assertThat(journal.findById(entry.getId()).getStatus()).isEqualTo(TradeStatus.PLANNED);
    }

    @Test
    void editFormPrefillsTheExistingTrade() throws Exception {
        TradeJournalEntry entry = plan();

        MvcResult result = mockMvc.perform(get("/journal/" + entry.getId() + "/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("journal-form"))
                .andExpect(model().attribute("editing", true))
                .andReturn();

        JournalEntryForm form = (JournalEntryForm) result.getModelAndView().getModel().get("form");
        assertThat(form.getTicker()).isEqualTo("VZ");
        assertThat(form.getShares()).isEqualTo(5);
    }

    /** Task 5.8 — the calculator hands a PASS straight to the journal. */
    @Test
    void journalThisTradeCreatesAPlannedEntryFromTheCalculator() throws Exception {
        mockMvc.perform(post("/journal/from-calculator")
                        .param("ticker", "vz")
                        .param("entry", "40.00")
                        .param("stop", "39.00")
                        .param("target", "43.60")
                        .param("ratio", "3.60")
                        .param("shares", "5")
                        .param("riskAmount", "5.00")
                        .param("setupType", "BREAKOUT"))
                .andExpect(status().is3xxRedirection());

        assertThat(journal.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getTicker()).isEqualTo("VZ");
            assertThat(e.getStatus()).isEqualTo(TradeStatus.PLANNED);
            assertThat(e.getRatio()).isEqualByComparingTo("3.60");
            assertThat(e.getShares()).isEqualTo(5);
        });
    }

    @Test
    void aPassingCalculatorResultOffersTheJournalHandoff() throws Exception {
        MvcResult result = mockMvc.perform(post("/analyze")
                        .param("ticker", "VZ")
                        .param("entry", "40.00")
                        .param("stop", "39.00")
                        .param("target", "43.60")
                        .param("accountSize", "500")
                        .param("riskAmount", "5.00"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("Journal this trade")
                .contains("/journal/from-calculator");
    }

    @Test
    void editSubmitUpdatesTheEntry() throws Exception {
        TradeJournalEntry entry = plan();
        long id = entry.getId();

        mockMvc.perform(post("/journal/" + id)
                        .param("ticker", "VZ")
                        .param("setupType", "REVERSAL")
                        .param("entry", "41.00")
                        .param("stop", "40.00")
                        .param("target", "45.00")
                        .param("ratio", "4.00")
                        .param("shares", "7")
                        .param("riskAmount", "7.00"))
                .andExpect(redirectedUrl("/journal/" + id));

        TradeJournalEntry updated = journal.findById(id);
        assertThat(updated.getSetupType()).isEqualTo(SetupType.REVERSAL);
        assertThat(updated.getShares()).isEqualTo(7);
        assertThat(updated.getEntry()).isEqualByComparingTo("41.00");
    }

    @Test
    void anInvalidEditRedisplaysTheFormStillInEditingMode() throws Exception {
        TradeJournalEntry entry = plan();

        mockMvc.perform(post("/journal/" + entry.getId())
                        .param("ticker", "")
                        .param("entry", "41.00")
                        .param("stop", "40.00")
                        .param("target", "45.00")
                        .param("shares", "7"))
                .andExpect(status().isOk())
                .andExpect(view().name("journal-form"))
                .andExpect(model().attribute("editing", true))
                .andExpect(model().attributeHasFieldErrors("form", "ticker"));
    }

    @Test
    void noFillThroughTheUi() throws Exception {
        TradeJournalEntry entry = plan();

        mockMvc.perform(post("/journal/" + entry.getId() + "/no-fill"))
                .andExpect(redirectedUrl("/journal/" + entry.getId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attributeExists("flash"));

        assertThat(journal.findById(entry.getId()).getStatus()).isEqualTo(TradeStatus.NO_FILL);
    }

    @Test
    void aNoFillOnAnAlreadyFilledTradeIsAFlashError() throws Exception {
        TradeJournalEntry entry = plan();
        journal.markFilled(entry.getId(), new BigDecimal("40.00"), 5);

        mockMvc.perform(post("/journal/" + entry.getId() + "/no-fill"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attributeExists("error"));

        assertThat(journal.findById(entry.getId()).getStatus()).isEqualTo(TradeStatus.FILLED);
    }

    @Test
    void aFillWithoutAPriceIsAFlashErrorRatherThanA500() throws Exception {
        TradeJournalEntry entry = plan();

        mockMvc.perform(post("/journal/" + entry.getId() + "/fill"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attributeExists("error"));

        assertThat(journal.findById(entry.getId()).getStatus()).isEqualTo(TradeStatus.PLANNED);
    }

    @Test
    void aClosedTradeShowsWhetherItCountsTowardTheScorecard() throws Exception {
        TradeJournalEntry entry = plan();
        journal.markFilled(entry.getId(), new BigDecimal("40.00"), 5);
        journal.close(entry.getId(), new BigDecimal("40.00"), "flat exit", true);   // SCRATCH

        MvcResult result = mockMvc.perform(get("/journal/" + entry.getId())).andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("excluded from the win rate")
                .contains("Delete entry");
    }

    @Test
    @DisplayName("a FAIL can be saved from the calculator as a rejected record")
    void aFailingSetupSavesAsRejected() throws Exception {
        mockMvc.perform(post("/journal/from-calculator")
                        .param("ticker", "ci")
                        .param("entry", "20.00")
                        .param("stop", "19.00")
                        .param("target", "21.87")
                        .param("ratio", "1.87")
                        .param("shares", "5")
                        .param("riskAmount", "5.00")
                        .param("setupType", "BREAKOUT")
                        .param("pass", "false")
                        .param("reason", "ratio 1.87 < 2.0"))
                .andExpect(status().is3xxRedirection());

        assertThat(journal.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getTicker()).isEqualTo("CI");
            assertThat(e.getStatus()).isEqualTo(TradeStatus.REJECTED);
            assertThat(e.getLessonText()).isEqualTo("Rejected by the rules: ratio 1.87 < 2.0");
        });
    }

    @Test
    void aFailingCalculatorResultOffersTheRejectedSave() throws Exception {
        MvcResult result = mockMvc.perform(post("/analyze")
                        .param("ticker", "CI")
                        .param("entry", "20.00")
                        .param("stop", "19.00")
                        .param("target", "21.87")
                        .param("accountSize", "500")
                        .param("riskAmount", "5.00"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("Save as rejected")
                .contains("never counts toward your win")
                .doesNotContain("Journal this trade");
    }

    @Test
    void deleteFromTheUiRedirectsToTheList() throws Exception {
        TradeJournalEntry entry = plan();

        mockMvc.perform(post("/journal/" + entry.getId() + "/delete"))
                .andExpect(redirectedUrl("/journal"));

        assertThat(journal.findAll()).isEmpty();
    }
}
