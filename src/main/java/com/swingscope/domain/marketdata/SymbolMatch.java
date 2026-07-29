package com.swingscope.domain.marketdata;

/** One hit from a ticker lookup. */
public record SymbolMatch(String symbol, String name, String exchange, String type) {
}
