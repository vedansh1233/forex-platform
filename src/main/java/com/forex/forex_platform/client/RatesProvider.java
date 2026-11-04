package com.forex.forex_platform.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.SortedMap;

/**
 * Source of exchange rates. Rates are "units of quote currency per 1 unit of base".
 * Implementations may throw {@link RatesUnavailableException} when the source can't be reached.
 */
public interface RatesProvider {

    /** Where the rates come from, shown to clients: "ECB" for live data, "SIMULATED" for offline rates. */
    String source();

    /**
     * Daily rates for {@code base} against each of {@code symbols}, from {@code from} to {@code to} inclusive.
     * Only business days are present (ECB publishes no weekend rates).
     *
     * @return date -> (currency -> rate), sorted by date ascending
     */
    SortedMap<LocalDate, Map<String, BigDecimal>> timeSeries(String base, Iterable<String> symbols,
                                                            LocalDate from, LocalDate to);

    class RatesUnavailableException extends RuntimeException {
        public RatesUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
