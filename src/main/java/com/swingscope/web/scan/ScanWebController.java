package com.swingscope.web.scan;

import com.swingscope.config.TradingRules;
import com.swingscope.service.scan.TierService;
import com.swingscope.service.scan.WatchlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/** The scan UI: paste a list or scan the watchlist, get a tiered shortlist back. */
@Controller
public class ScanWebController {

    private static final Logger log = LoggerFactory.getLogger(ScanWebController.class);

    private final TierService tierService;
    private final WatchlistService watchlist;
    private final TradingRules rules;

    public ScanWebController(TierService tierService, WatchlistService watchlist, TradingRules rules) {
        this.tierService = tierService;
        this.watchlist = watchlist;
        this.rules = rules;
    }

    @GetMapping("/scan")
    public String form(Model model) {
        model.addAttribute("watchlist", watchlist.findAll());
        return "scan";
    }

    @PostMapping("/scan")
    public String scan(@RequestParam(required = false) String tickers, Model model) {
        List<String> parsed = TierService.parseTickers(tickers);
        if (parsed.isEmpty()) {
            model.addAttribute("error", "Paste at least one ticker.");
            model.addAttribute("watchlist", watchlist.findAll());
            return "scan";
        }
        log.info("Scanning {} pasted ticker(s) from the UI", parsed.size());
        model.addAttribute("result", tierService.scan(parsed));
        model.addAttribute("pasted", tickers);
        model.addAttribute("watchlist", watchlist.findAll());
        return "scan";
    }

    @PostMapping("/scan/watchlist")
    public String scanWatchlist(Model model) {
        List<String> tickers = watchlist.tickers();
        if (tickers.isEmpty()) {
            model.addAttribute("error", "Your watchlist is empty — add a ticker first.");
            model.addAttribute("watchlist", watchlist.findAll());
            return "scan";
        }
        log.info("Scanning the {}-name watchlist from the UI", tickers.size());
        model.addAttribute("result", tierService.scan(tickers));
        model.addAttribute("watchlist", watchlist.findAll());
        return "scan";
    }

    // ------------------------------------------------------------------------------- watchlist

    @PostMapping("/watchlist")
    public String addToWatchlist(@RequestParam String ticker,
                                 @RequestParam(required = false) String note,
                                 RedirectAttributes redirect) {
        try {
            watchlist.add(ticker, note);
            redirect.addFlashAttribute("flash", ticker.toUpperCase() + " added to your watchlist.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/scan";
    }

    @PostMapping("/watchlist/{id}/delete")
    public String removeFromWatchlist(@PathVariable Long id, RedirectAttributes redirect) {
        watchlist.remove(id);
        redirect.addFlashAttribute("flash", "Removed from your watchlist.");
        return "redirect:/scan";
    }

    /**
     * Task 4.4 — hands a scanned candidate to the calculator with <strong>entry pre-filled from the
     * current price</strong>. Stop and target stay deliberately blank: no API supplies support and
     * resistance, and reading those two levels off the chart is the judgment this tool keeps human.
     */
    @GetMapping("/plan")
    public String planTrade(@RequestParam String ticker,
                            @RequestParam(required = false) java.math.BigDecimal entry,
                            Model model) {
        log.info("Pre-filling the calculator for {} at {}", ticker, entry);
        com.swingscope.web.TradeSetupForm form = new com.swingscope.web.TradeSetupForm();
        form.setTicker(ticker == null ? null : ticker.trim().toUpperCase());
        form.setEntry(entry);
        form.setAccountSize(rules.defaultAccountSize());
        form.setRiskAmount(rules.defaultRiskAmount());
        model.addAttribute("form", form);
        model.addAttribute("prefilled", true);
        return "calculator";
    }
}
