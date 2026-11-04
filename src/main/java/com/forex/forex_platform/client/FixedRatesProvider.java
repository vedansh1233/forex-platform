package com.forex.forex_platform.client;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Offline, deterministic rates. Used for tests and local development without internet
 * ({@code app.rates.provider=fixed}), and by {@link com.forex.forex_platform.service.RatesService}
 * as a labelled fallback when the live provider is down. Values approximate real-world
 * levels and drift gently over time so charts have shape. Weekends are skipped like ECB data.
 */
public class FixedRatesProvider implements RatesProvider {

    @Override
    public String source() {
        return "SIMULATED";
    }

    /** Units of each currency per 1 USD. */
    static final Map<String, BigDecimal> USD_RATES = Map.of(
            "USD", new BigDecimal("1"),
            "EUR", new BigDecimal("0.90"),
            "GBP", new BigDecimal("0.78"),
            "JPY", new BigDecimal("150"),
            "CHF", new BigDecimal("0.88"),
            "CAD", new BigDecimal("1.36"),
            "AUD", new BigDecimal("1.52"),
            "NZD", new BigDecimal("1.66")
    );

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);

    @Override
    public SortedMap<LocalDate, Map<String, BigDecimal>> timeSeries(String base, Iterable<String> symbols,
                                                                   LocalDate from, LocalDate to) {
        SortedMap<LocalDate, Map<String, BigDecimal>> result = new TreeMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            Map<String, BigDecimal> rates = new LinkedHashMap<>();
            for (String symbol : symbols) {
                rates.put(symbol, rate(base, symbol, day));
            }
            result.put(day, rates);
        }
        return result;
    }

    private BigDecimal rate(String base, String quote, LocalDate day) {
        // Every pair derives from per-currency USD levels, so cross rates stay consistent
        // (EUR/USD * USD/EUR == 1) and values are the same whatever date range is requested.
        BigDecimal quotePerUsd = USD_RATES.get(quote).multiply(BigDecimal.valueOf(drift(quote, day)), MC);
        BigDecimal basePerUsd = USD_RATES.get(base).multiply(BigDecimal.valueOf(drift(base, day)), MC);
        return quotePerUsd.divide(basePerUsd, MC).setScale(6, RoundingMode.HALF_EVEN);
    }

    /**
     * Deterministic multiplicative drift (roughly ±4%) for a currency against USD: a sum of
     * waves with unrelated periods, phase-shifted per currency, so the series looks like
     * market movement rather than a single sine wave.
     */
    static double drift(String currency, LocalDate day) {
        if (currency.equals("USD")) {
            return 1.0;
        }
        double t = day.toEpochDay();
        double phase = Math.floorMod(currency.hashCode(), 997) / 997.0 * 2 * Math.PI;
        return 1
                + 0.022 * Math.sin(t / 97.0 + phase)
                + 0.011 * Math.sin(t / 31.0 + phase * 1.7)
                + 0.006 * Math.sin(t / 8.3 + phase * 2.3)
                + 0.003 * Math.sin(t / 2.9 + phase * 3.1);
    }
}
