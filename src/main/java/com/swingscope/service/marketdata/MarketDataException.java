package com.swingscope.service.marketdata;

/** Anything that went wrong talking to a data provider. */
public class MarketDataException extends RuntimeException {

    private final String provider;

    public MarketDataException(String provider, String message) {
        super(message);
        this.provider = provider;
    }

    public MarketDataException(String provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    public String provider() {
        return provider;
    }
}
