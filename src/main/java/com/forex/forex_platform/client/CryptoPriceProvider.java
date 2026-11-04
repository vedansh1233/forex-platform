package com.forex.forex_platform.client;

import com.forex.forex_platform.client.RatesProvider.RatesUnavailableException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/** A source of cryptocurrency prices in USD. */
public interface CryptoPriceProvider {

    /** Label shown to users, e.g. "COINBASE" or "SIMULATED". */
    String source();

    /**
     * Current price and the price 24 hours ago, in USD, for each symbol.
     *
     * @throws RatesUnavailableException if the source can't be reached or returns bad data
     */
    Map<String, Ticker> tickers(List<String> symbols);

    /**
     * Daily closing prices in USD (UTC days), oldest first.
     *
     * @throws RatesUnavailableException if the source can't be reached or returns bad data
     */
    SortedMap<LocalDate, BigDecimal> dailyCloses(String symbol, LocalDate from, LocalDate to);

    record Ticker(BigDecimal price, BigDecimal open24h) {
    }
}
