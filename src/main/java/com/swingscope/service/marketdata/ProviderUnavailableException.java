package com.swingscope.service.marketdata;

/**
 * The provider is configured off, has no key, or does not offer the capability at all — for
 * example Finnhub's candles, which moved to its premium tier and return HTTP 403 on a free key.
 */
public class ProviderUnavailableException extends MarketDataException {

    public ProviderUnavailableException(String provider, String message) {
        super(provider, message);
    }
}
