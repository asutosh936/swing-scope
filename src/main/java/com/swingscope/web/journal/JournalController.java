package com.swingscope.web.journal;

import com.swingscope.domain.journal.SetupType;
import com.swingscope.domain.journal.TradeJournalEntry;
import com.swingscope.service.journal.InvalidTransitionException;
import com.swingscope.service.journal.TradeJournalService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.swingscope.config.OpenApiConfig;
import com.swingscope.domain.journal.JournalStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything journal-related: the scorecard list, per-trade detail, the create/edit form, the status
 * transitions, and the read-only JSON endpoints.
 *
 * <p>Writes happen through the UI only. An earlier version mirrored every write on {@code /api} as
 * well, which meant two paths to keep in step for no benefit — the pages never called the API.
 */
@Controller
public class JournalController {

    private static final Logger log = LoggerFactory.getLogger(JournalController.class);

    private final TradeJournalService journal;

    public JournalController(TradeJournalService journal) {
        this.journal = journal;
    }

    @ModelAttribute("setupTypes")
    SetupType[] setupTypes() {
        return SetupType.values();
    }

    /** 5.3 — the running scorecard. */
    @GetMapping("/journal")
    public String list(Model model) {
        log.info("Rendering journal list");
        model.addAttribute("entries", journal.findAll());
        model.addAttribute("stats", journal.stats());
        return "journal";
    }

    /** 5.4 — one trade's full lifecycle. */
    @GetMapping("/journal/{id}")
    public String detail(@PathVariable Long id, Model model) {
        log.info("Rendering journal detail for #{}", id);
        model.addAttribute("entry", journal.findById(id));
        return "journal-detail";
    }

    /** 5.5 — create form. */
    @GetMapping("/journal/new")
    public String createForm(Model model) {
        model.addAttribute("form", new JournalEntryForm());
        model.addAttribute("editing", false);
        return "journal-form";
    }

    @GetMapping("/journal/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("form", JournalEntryForm.of(journal.findById(id)));
        model.addAttribute("editing", true);
        model.addAttribute("entryId", id);
        return "journal-form";
    }

    @PostMapping("/journal")
    public String create(@Valid @ModelAttribute("form") JournalEntryForm form,
                         BindingResult binding, Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            log.warn("Journal create rejected: {} field error(s)", binding.getErrorCount());
            model.addAttribute("editing", false);
            return "journal-form";
        }
        TradeJournalEntry created = journal.create(form.toEntity());
        redirect.addFlashAttribute("flash", "Trade #%d journalled for %s."
                .formatted(created.getId(), created.getTicker()));
        return "redirect:/journal/" + created.getId();
    }

    @PostMapping("/journal/{id}")
    public String update(@PathVariable Long id, @Valid @ModelAttribute("form") JournalEntryForm form,
                         BindingResult binding, Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            model.addAttribute("editing", true);
            model.addAttribute("entryId", id);
            return "journal-form";
        }
        journal.update(id, form.toEntity());
        redirect.addFlashAttribute("flash", "Entry #%d updated.".formatted(id));
        return "redirect:/journal/" + id;
    }

    // --------------------------------------------------------------------- status transitions

    @PostMapping("/journal/{id}/fill")
    public String fill(@PathVariable Long id,
                       @RequestParam(required = false) BigDecimal fillPrice,
                       @RequestParam(required = false) Integer actualShares,
                       RedirectAttributes redirect) {
        try {
            journal.markFilled(id, fillPrice, actualShares);
            redirect.addFlashAttribute("flash", "Marked filled at " + fillPrice + ".");
        } catch (InvalidTransitionException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/journal/" + id;
    }

    @PostMapping("/journal/{id}/no-fill")
    public String noFill(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            journal.markNoFill(id);
            redirect.addFlashAttribute("flash", "Marked no-fill — excluded from the scorecard.");
        } catch (InvalidTransitionException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/journal/" + id;
    }

    @PostMapping("/journal/{id}/close")
    public String close(@PathVariable Long id,
                        @RequestParam(required = false) BigDecimal exitPrice,
                        @RequestParam(required = false) String lessonText,
                        @RequestParam(required = false) Boolean rulesFollowed,
                        RedirectAttributes redirect) {
        try {
            TradeJournalEntry closed = journal.close(id, exitPrice, lessonText, rulesFollowed);
            redirect.addFlashAttribute("flash", "Closed as %s, P&L %s."
                    .formatted(closed.getStatus().getLabel(), closed.getRealizedPnl()));
        } catch (InvalidTransitionException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/journal/" + id;
    }

    @PostMapping("/journal/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        journal.delete(id);
        redirect.addFlashAttribute("flash", "Entry #%d deleted.".formatted(id));
        return "redirect:/journal";
    }

    // ------------------------------------------------------------------- JSON, for scripting

    @Operation(
            summary = "Every journal entry, newest first",
            description = """
                    The whole record: planned trades, filled positions, closed outcomes, no-fills, and                     setups the rules rejected.

                    `status` drives everything downstream:
                    * `PLANNED` — sized and logged, not yet in the market
                    * `FILLED` — live; `fillPrice` is what you actually got
                    * `CLOSED_WIN` / `CLOSED_LOSS` — **the only statuses that count** toward win rate,                     expectancy and graduation
                    * `SCRATCH` — exited flat; `NO_FILL` — never triggered; `REJECTED` — the rules                     refused it. None of these three are evidence about the strategy, so all three are                     excluded from the scorecard.

                    `realizedPnl` is `(exitPrice − fillPrice) × shares`, long only, no commissions,                     and is null until the trade closes.
                    """,
            tags = OpenApiConfig.TAG_JOURNAL)
    @ApiResponse(responseCode = "200", description = "All entries, most recently planned first.",
            content = @Content(array = @io.swagger.v3.oas.annotations.media.ArraySchema(
                    schema = @Schema(implementation = TradeJournalEntry.class))))
    @GetMapping("/api/journal")
    @ResponseBody
    public List<TradeJournalEntry> listApi() {
        log.info("GET /api/journal");
        return journal.findAll();
    }

    @Operation(
            summary = "The running scorecard and graduation progress",
            description = """
                    Totals over **completed trades only** — wins and losses. Scratches, no-fills and                     rejected setups are counted separately and excluded from every average, because                     they say nothing about whether the strategy works.

                    * `winRate` — percent, one decimal
                    * `expectancy` — average dollars per completed trade (`netPnl ÷ closedCount`)
                    * `losersWithRulesFollowed` — losses you still took by the book, which matters                     more than the loss itself

                    `graduationMet` is true only when **all three** gates pass: at least                     `graduationTarget` closed trades, a positive net total, and the rules followed on                     every single loser. It reports a fact about your record — it is not permission to                     trade real money.
                    """,
            tags = OpenApiConfig.TAG_JOURNAL)
    @ApiResponse(responseCode = "200", description = "Scorecard totals; all zeros on an empty journal.",
            content = @Content(schema = @Schema(implementation = JournalStats.class),
                    examples = @ExampleObject(value = """
                            {"totalEntries":2,"openTrades":1,"closedCount":1,"wins":1,"losses":0,
                             "scratches":0,"noFills":0,"rejected":1,"winRate":100.0,"netPnl":20.00,
                             "expectancy":20.00,"averageWin":20.00,"averageLoss":0,
                             "losersWithRulesFollowed":0,"graduationTarget":25,
                             "graduationPercent":4,"graduationMet":false}""")))
    @GetMapping("/api/journal/stats")
    @ResponseBody
    public JournalStats statsApi() {
        log.info("GET /api/journal/stats");
        return journal.stats();
    }

    @Operation(
            summary = "One journal entry by id",
            description = "The full lifecycle of a single trade: the plan, the execution, the outcome, "
                    + "and the one-sentence lesson.",
            tags = OpenApiConfig.TAG_JOURNAL)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The entry.",
                    content = @Content(schema = @Schema(implementation = TradeJournalEntry.class))),
            @ApiResponse(responseCode = "404", description = "No entry with that id.",
                    content = @Content(examples = @ExampleObject(value = """
                            {"timestamp":"2026-07-29T15:14:35Z","status":404,
                             "message":"no journal entry with id 99999","provider":"journal"}""")))
    })
    @GetMapping("/api/journal/{id}")
    @ResponseBody
    public TradeJournalEntry getApi(
            @Parameter(description = "Journal entry id.", example = "1") @PathVariable Long id) {
        log.info("GET /api/journal/{}", id);
        return journal.findById(id);
    }
}
