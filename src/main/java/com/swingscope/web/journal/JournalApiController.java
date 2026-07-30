package com.swingscope.web.journal;

import com.swingscope.domain.journal.JournalStats;
import com.swingscope.domain.journal.TradeJournalEntry;
import com.swingscope.service.journal.TradeJournalService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** JSON API for the journal. The UI and Phase 4's "Plan this trade" both go through this service. */
@RestController
@RequestMapping("/api/journal")
public class JournalApiController {

    private static final Logger log = LoggerFactory.getLogger(JournalApiController.class);

    private final TradeJournalService journal;

    public JournalApiController(TradeJournalService journal) {
        this.journal = journal;
    }

    @GetMapping
    public List<TradeJournalEntry> list() {
        log.info("GET /api/journal");
        return journal.findAll();
    }

    @GetMapping("/stats")
    public JournalStats stats() {
        log.info("GET /api/journal/stats");
        return journal.stats();
    }

    @GetMapping("/{id}")
    public TradeJournalEntry get(@PathVariable Long id) {
        log.info("GET /api/journal/{}", id);
        return journal.findById(id);
    }

    @PostMapping
    public ResponseEntity<TradeJournalEntry> create(@Valid @RequestBody JournalRequests.CreateEntry request) {
        log.info("POST /api/journal for {}", request.ticker());
        TradeJournalEntry created = journal.create(request.toEntity());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Records a setup the rules turned down. Terminal on arrival: it never opened, so it can't be
     * filled or closed, and it stays out of the win rate, expectancy and the graduation count.
     */
    @PostMapping("/rejected")
    public ResponseEntity<TradeJournalEntry> createRejected(
            @Valid @RequestBody JournalRequests.RejectEntry request) {
        log.info("POST /api/journal/rejected for {} — {}", request.ticker(), request.reason());
        TradeJournalEntry created = journal.create(request.toEntity());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public TradeJournalEntry update(@PathVariable Long id,
                                    @Valid @RequestBody JournalRequests.CreateEntry request) {
        log.info("PUT /api/journal/{}", id);
        return journal.update(id, request.toEntity());
    }

    @PostMapping("/{id}/fill")
    public TradeJournalEntry fill(@PathVariable Long id,
                                  @Valid @RequestBody JournalRequests.FillRequest request) {
        log.info("POST /api/journal/{}/fill at {}", id, request.fillPrice());
        return journal.markFilled(id, request.fillPrice(), request.actualShares());
    }

    @PostMapping("/{id}/no-fill")
    public TradeJournalEntry noFill(@PathVariable Long id) {
        log.info("POST /api/journal/{}/no-fill", id);
        return journal.markNoFill(id);
    }

    @PostMapping("/{id}/close")
    public TradeJournalEntry close(@PathVariable Long id,
                                   @Valid @RequestBody JournalRequests.CloseRequest request) {
        log.info("POST /api/journal/{}/close at {}", id, request.exitPrice());
        return journal.close(id, request.exitPrice(), request.lessonText(), request.rulesFollowed());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        log.info("DELETE /api/journal/{}", id);
        journal.delete(id);
        return ResponseEntity.noContent().build();
    }
}
