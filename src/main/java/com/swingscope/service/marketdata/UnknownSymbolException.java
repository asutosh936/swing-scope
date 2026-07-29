package com.swingscope.service.marketdata;

/** The provider has no data for this ticker — a typo, a delisting, or a non-US listing. */
public class UnknownSymbolException extends MarketDataException {

    private final String symbol;

    public UnknownSymbolException(String provider, String symbol) {
        super(provider, "unknown symbol '%s' at %s".formatted(symbol, provider));
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
