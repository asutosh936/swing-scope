package com.swingscope.service.marketdata;

import com.swingscope.domain.marketdata.Candles;
import com.swingscope.domain.marketdata.CompanyProfile;
import com.swingscope.domain.marketdata.EarningsEvent;
import com.swingscope.domain.marketdata.MarketStatus;
import com.swingscope.domain.marketdata.NewsItem;
import com.swingscope.domain.marketdata.Quote;
import com.swingscope.domain.marketdata.SymbolMatch;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * One data source. Providers declare which capabilities they actually offer, so a source can be
 * swapped or split by capability without services knowing which one answered.
 *
 * <p>Every unimplemented method throws {@link ProviderUnavailableException} by default — an
 * implementation only overrides what it supports, and must declare the same set in
 * {@link #capabilities()}.
 */
public interface MarketDataProvider {

    enum Capability {
        QUOTE,
        DAILY_CANDLES,
        SYMBOL_SEARCH,
        EARNINGS,
        MARKET_STATUS,
        COMPANY_PROFILE,
        COMPANY_NEWS
    }

    /** Short name used in logs and error messages, e.g. {@code twelvedata}. */
    String name();

    Set<Capability> capabilities();

    /** Whether this provider is configured and switched on. A missing API key makes it unusable. */
    boolean isAvailable();

    default boolean supports(Capability capability) {
        return isAvailable() && capabilities().contains(capability);
    }

    default Quote getQuote(String symbol) {
        throw unsupported(Capability.QUOTE);
    }

    /**
     * @param outputSize how many daily bars to request; EMA200 needs at least 200
     */
    default Candles getDailyCandles(String symbol, int outputSize) {
        throw unsupported(Capability.DAILY_CANDLES);
    }

    default List<SymbolMatch> search(String query) {
        throw unsupported(Capability.SYMBOL_SEARCH);
    }

    default List<EarningsEvent> getEarnings(String symbol, LocalDate from, LocalDate to) {
        throw unsupported(Capability.EARNINGS);
    }

    default MarketStatus getMarketStatus() {
        throw unsupported(Capability.MARKET_STATUS);
    }

    default CompanyProfile getCompanyProfile(String symbol) {
        throw unsupported(Capability.COMPANY_PROFILE);
    }

    /** Recent stories for a symbol. Context for the human (and Phase 5's AI summary), never a signal. */
    default List<NewsItem> getCompanyNews(String symbol, LocalDate from, LocalDate to) {
        throw unsupported(Capability.COMPANY_NEWS);
    }

    private ProviderUnavailableException unsupported(Capability capability) {
        return new ProviderUnavailableException(name(),
                "%s does not provide %s".formatted(name(), capability));
    }
}
