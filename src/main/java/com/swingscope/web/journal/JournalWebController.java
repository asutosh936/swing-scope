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

import java.math.BigDecimal;

/** The journal UI: scorecard list, per-trade detail, and the create/edit form. */
@Controller
public class JournalWebController {

    private static final Logger log = LoggerFactory.getLogger(JournalWebController.class);

    private final TradeJournalService journal;

    public JournalWebController(TradeJournalService journal) {
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
}
