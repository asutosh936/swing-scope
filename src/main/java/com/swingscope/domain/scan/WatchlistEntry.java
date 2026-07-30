package com.swingscope.domain.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/** A ticker the user watches regularly, so a stable list isn't re-pasted every session. */
@Entity
@Table(name = "watchlist_entry")
public class WatchlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 12)
    private String ticker;

    @Column(length = 120)
    private String note;

    @Column(nullable = false)
    private LocalDate dateAdded = LocalDate.now();

    protected WatchlistEntry() {
        // for JPA
    }

    public WatchlistEntry(String ticker, String note) {
        this.ticker = ticker;
        this.note = note;
        this.dateAdded = LocalDate.now();
    }

    public Long getId() {
        return id;
    }

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDate getDateAdded() {
        return dateAdded;
    }

    public void setDateAdded(LocalDate dateAdded) {
        this.dateAdded = dateAdded;
    }
}
