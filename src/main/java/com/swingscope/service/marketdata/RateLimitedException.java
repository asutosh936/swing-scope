package com.swingscope.service.marketdata;

/** The provider's rate limit was hit and retrying with backoff did not clear it. */
public class RateLimitedException extends MarketDataException {

    public RateLimitedException(String provider, String message) {
        super(provider, message);
    }
}
