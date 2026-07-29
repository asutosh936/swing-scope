package com.swingscope.domain.marketdata;

/** Whether the US market is currently open. Informational only — nothing gates on it. */
public record MarketStatus(String exchange, boolean open, String session, String holiday) {
}
