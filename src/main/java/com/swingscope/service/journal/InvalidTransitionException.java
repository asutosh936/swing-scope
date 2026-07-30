package com.swingscope.service.journal;

import com.swingscope.domain.journal.TradeStatus;

/** An illegal status move, e.g. closing a trade that never filled. */
public class InvalidTransitionException extends RuntimeException {

    public InvalidTransitionException(TradeStatus from, TradeStatus to) {
        super("cannot move a trade from %s to %s".formatted(from, to));
    }

    public InvalidTransitionException(String message) {
        super(message);
    }
}
