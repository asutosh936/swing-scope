package com.swingscope.service.journal;

public class JournalEntryNotFoundException extends RuntimeException {

    public JournalEntryNotFoundException(Long id) {
        super("no journal entry with id " + id);
    }
}
