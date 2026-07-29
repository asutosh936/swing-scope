package com.swingscope.domain.marketdata;

import java.time.LocalDate;

/**
 * A scheduled or reported earnings date.
 *
 * @param hour provider's session hint — {@code bmo} before market open, {@code amc} after close
 */
public record EarningsEvent(String symbol, LocalDate date, String hour, Integer quarter, Integer year) {
}
