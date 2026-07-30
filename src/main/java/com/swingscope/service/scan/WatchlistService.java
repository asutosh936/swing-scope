package com.swingscope.service.scan;

import com.swingscope.domain.scan.WatchlistEntry;
import com.swingscope.repository.WatchlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** The user's stable ~15–20 names, so recurring tickers aren't re-pasted every session. */
@Service
public class WatchlistService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistService.class);

    private final WatchlistRepository repository;

    public WatchlistService(WatchlistRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<WatchlistEntry> findAll() {
        return repository.findAllByOrderByTickerAsc();
    }

    @Transactional(readOnly = true)
    public List<String> tickers() {
        return findAll().stream().map(WatchlistEntry::getTicker).toList();
    }

    @Transactional(readOnly = true)
    public WatchlistEntry findById(Long id) {
        return repository.findById(id).orElseThrow(() -> new WatchlistEntryNotFoundException(id));
    }

    /** Adding a ticker already on the list is a no-op, not an error. */
    @Transactional
    public WatchlistEntry add(String rawTicker, String note) {
        String ticker = normalise(rawTicker);
        return repository.findByTicker(ticker)
                .map(existing -> {
                    log.info("{} is already on the watchlist (#{}) — left as is", ticker, existing.getId());
                    return existing;
                })
                .orElseGet(() -> {
                    WatchlistEntry saved = repository.save(new WatchlistEntry(ticker, note));
                    log.info("Added {} to the watchlist (#{})", ticker, saved.getId());
                    return saved;
                });
    }

    /** Bulk add from a pasted list — useful right after a scan turns up keepers. */
    @Transactional
    public List<WatchlistEntry> addAll(List<String> tickers) {
        return tickers.stream().map(t -> add(t, null)).toList();
    }

    @Transactional
    public WatchlistEntry updateNote(Long id, String note) {
        WatchlistEntry entry = findById(id);
        entry.setNote(note);
        log.info("Updated note on watchlist #{} ({})", id, entry.getTicker());
        return entry;
    }

    @Transactional
    public void remove(Long id) {
        WatchlistEntry entry = findById(id);
        repository.delete(entry);
        log.info("Removed {} from the watchlist", entry.getTicker());
    }

    @Transactional
    public void removeByTicker(String rawTicker) {
        String ticker = normalise(rawTicker);
        repository.findByTicker(ticker).ifPresentOrElse(entry -> {
            repository.delete(entry);
            log.info("Removed {} from the watchlist", ticker);
        }, () -> {
            throw new WatchlistEntryNotFoundException(ticker);
        });
    }

    private static String normalise(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            throw new IllegalArgumentException("ticker is required");
        }
        return ticker.trim().toUpperCase();
    }
}
