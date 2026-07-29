package com.swingscope.web;

import com.swingscope.service.marketdata.MarketDataException;
import com.swingscope.service.marketdata.ProviderUnavailableException;
import com.swingscope.service.marketdata.RateLimitedException;
import com.swingscope.service.marketdata.UnknownSymbolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Turns rejected requests into a small, predictable JSON body — and logs why they were rejected. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new TreeMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }
        log.warn("Request rejected by validation: {}", fieldErrors);
        return ResponseEntity.badRequest().body(body("invalid trade setup", fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Request rejected — unreadable body: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(body("malformed request body", Map.of()));
    }

    /** A ticker the provider has no data for — the caller's input is wrong, so 404. */
    @ExceptionHandler(UnknownSymbolException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownSymbol(UnknownSymbolException ex) {
        log.warn("Unknown symbol '{}' at provider {}", ex.symbol(), ex.provider());
        return status(HttpStatus.NOT_FOUND, ex.getMessage(), ex.provider());
    }

    /** Free-tier budget exhausted. 429 back to the caller, and Retry-After so a client can wait. */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimited(RateLimitedException ex) {
        log.error("Provider {} rate limit hit: {}", ex.provider(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "60")
                .body(payload(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), ex.provider()));
    }

    /** Provider switched off, missing a key, or the endpoint needs a paid plan. */
    @ExceptionHandler(ProviderUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleProviderUnavailable(ProviderUnavailableException ex) {
        log.error("Provider {} unavailable: {}", ex.provider(), ex.getMessage());
        return status(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex.provider());
    }

    /** Anything else that went wrong upstream is a bad gateway, not our bug. */
    @ExceptionHandler(MarketDataException.class)
    public ResponseEntity<Map<String, Object>> handleMarketData(MarketDataException ex) {
        log.error("Market data call failed at provider {}: {}", ex.provider(), ex.getMessage(), ex);
        return status(HttpStatus.BAD_GATEWAY, ex.getMessage(), ex.provider());
    }

    private static ResponseEntity<Map<String, Object>> status(HttpStatus status, String message,
                                                              String provider) {
        return ResponseEntity.status(status).body(payload(status, message, provider));
    }

    private static Map<String, Object> payload(HttpStatus status, String message, String provider) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("message", message);
        body.put("provider", provider);
        return body;
    }

    private static Map<String, Object> body(String message, Map<String, String> fieldErrors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("message", message);
        body.put("fieldErrors", fieldErrors);
        return body;
    }
}
