package com.swingscope.domain.marketdata;

import java.math.BigDecimal;

/**
 * @param marketCap in millions of the listed currency, as Finnhub reports it
 */
public record CompanyProfile(String symbol, String name, String exchange, BigDecimal marketCap,
                             String industry) {
}
