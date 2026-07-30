package com.swingscope.web.scan;

import com.swingscope.domain.scan.ScanResult;
import com.swingscope.domain.scan.WatchlistEntry;
import com.swingscope.service.scan.TierService;
import com.swingscope.service.scan.WatchlistService;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** JSON API for the scan and the watchlist. */
@RestController
@RequestMapping("/api")
public class ScanApiController {

    private static final Logger log = LoggerFactory.getLogger(ScanApiController.class);

    private final TierService tierService;
    private final WatchlistService watchlist;

    public ScanApiController(TierService tierService, WatchlistService watchlist) {
        this.tierService = tierService;
        this.watchlist = watchlist;
    }

    /** Accepts either a parsed list or one pasted blob. */
    public record ScanRequest(List<String> tickers, String raw) {
    }

    public record WatchlistRequest(@NotBlank String ticker, String note) {
    }

    public record NoteRequest(String note) {
    }

    @PostMapping("/scan")
    public ScanResult scan(@RequestBody ScanRequest request) {
        List<String> tickers = request.tickers() != null && !request.tickers().isEmpty()
                ? request.tickers()
                : TierService.parseTickers(request.raw());
        log.info("POST /api/scan with {} ticker(s)", tickers.size());
        return tierService.scan(tickers);
    }

    /** Scan the saved watchlist without pasting anything. */
    @PostMapping("/scan/watchlist")
    public ScanResult scanWatchlist() {
        List<String> tickers = watchlist.tickers();
        log.info("POST /api/scan/watchlist — {} saved ticker(s)", tickers.size());
        return tierService.scan(tickers);
    }

    @GetMapping("/watchlist")
    public List<WatchlistEntry> list() {
        return watchlist.findAll();
    }

    @PostMapping("/watchlist")
    public ResponseEntity<WatchlistEntry> add(@RequestBody WatchlistRequest request) {
        WatchlistEntry entry = watchlist.add(request.ticker(), request.note());
        return ResponseEntity.status(HttpStatus.CREATED).body(entry);
    }

    @PostMapping("/watchlist/{id}/note")
    public WatchlistEntry updateNote(@PathVariable Long id, @RequestBody NoteRequest request) {
        return watchlist.updateNote(id, request.note());
    }

    @DeleteMapping("/watchlist/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long id) {
        watchlist.remove(id);
        return ResponseEntity.noContent().build();
    }
}
