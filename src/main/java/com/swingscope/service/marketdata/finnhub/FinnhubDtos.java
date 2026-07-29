package com.swingscope.service.marketdata.finnhub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Wire shapes for Finnhub's still-free endpoints. Unlike Twelve Data, numbers arrive as real JSON
 * numbers and failures use real HTTP status codes.
 *
 * <p>Deliberately absent: {@code /stock/candle}. It moved to the premium tier and returns HTTP 403
 * on a free key, which is why candles come from Twelve Data.
 */
final class FinnhubDtos {

    private FinnhubDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EarningsCalendarResponse(@JsonProperty("earningsCalendar") List<Entry> earningsCalendar) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Entry(
                String symbol,
                String date,
                String hour,
                Integer quarter,
                Integer year,
                BigDecimal epsActual,
                BigDecimal epsEstimate
        ) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MarketStatusResponse(
            String exchange,
            String holiday,
            @JsonProperty("isOpen") Boolean isOpen,
            String session,
            String timezone
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CompanyProfileResponse(
            String ticker,
            String name,
            String exchange,
            String finnhubIndustry,
            BigDecimal marketCapitalization,
            String currency
    ) {
    }
}
