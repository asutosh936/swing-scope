package com.swingscope.web;

import com.swingscope.domain.marketdata.MarketSnapshot;
import com.swingscope.domain.marketdata.MarketStatus;
import com.swingscope.domain.marketdata.SymbolMatch;
import com.swingscope.service.marketdata.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only market data. Nothing here places an order or recommends one. */
@RestController
@RequestMapping("/api/marketdata")
public class MarketDataController {

    private static final Logger log = LoggerFactory.getLogger(MarketDataController.class);

    private final MarketDataService marketData;

    public MarketDataController(MarketDataService marketData) {
        this.marketData = marketData;
    }

    @GetMapping("/{symbol}")
    public ResponseEntity<MarketSnapshot> snapshot(@PathVariable String symbol) {
        log.info("GET /api/marketdata/{}", symbol);
        return ResponseEntity.ok(marketData.getSnapshot(symbol));
    }

    @GetMapping("/search")
    public ResponseEntity<List<SymbolMatch>> search(@RequestParam("q") String query) {
        log.info("GET /api/marketdata/search?q={}", query);
        return ResponseEntity.ok(marketData.search(query));
    }

    @GetMapping("/status")
    public ResponseEntity<MarketStatus> marketStatus() {
        log.info("GET /api/marketdata/status");
        return ResponseEntity.ok(marketData.getMarketStatus());
    }
}
