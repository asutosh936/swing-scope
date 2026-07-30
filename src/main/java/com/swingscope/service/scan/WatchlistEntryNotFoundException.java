package com.swingscope.service.scan;

public class WatchlistEntryNotFoundException extends RuntimeException {

    public WatchlistEntryNotFoundException(Long id) {
        super("no watchlist entry with id " + id);
    }

    public WatchlistEntryNotFoundException(String ticker) {
        super("'" + ticker + "' is not on the watchlist");
    }
}
