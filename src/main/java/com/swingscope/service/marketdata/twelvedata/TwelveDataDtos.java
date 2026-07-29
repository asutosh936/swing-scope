package com.swingscope.service.marketdata.twelvedata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wire shapes for Twelve Data.
 *
 * <p>Two traps this maps around. First, every numeric field arrives as a JSON <em>string</em>
 * ({@code "close": "40.12"}), so these are Strings and get parsed deliberately. Second, Twelve Data
 * reports application errors with HTTP 200 and a {@code {"status":"error","code":429}} body, so
 * every response carries {@code status} and {@code code} and the client checks them.
 */
final class TwelveDataDtos {

    private TwelveDataDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuoteResponse(
            String symbol,
            String name,
            String exchange,
            String currency,
            String datetime,
            String open,
            String high,
            String low,
            String close,
            String volume,
            @JsonProperty("previous_close") String previousClose,
            String change,
            @JsonProperty("percent_change") String percentChange,
            @JsonProperty("average_volume") String averageVolume,
            @JsonProperty("is_market_open") Boolean isMarketOpen,
            String status,
            Integer code,
            String message
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TimeSeriesResponse(
            Meta meta,
            List<Value> values,
            String status,
            Integer code,
            String message
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Meta(String symbol, String interval, String currency, String exchange) {
        }

        /** One bar. Twelve Data returns these newest-first. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Value(String datetime, String open, String high, String low, String close, String volume) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SymbolSearchResponse(List<Match> data, String status, Integer code, String message) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Match(
                String symbol,
                @JsonProperty("instrument_name") String instrumentName,
                String exchange,
                @JsonProperty("instrument_type") String instrumentType,
                String country,
                String currency
        ) {
        }
    }
}
