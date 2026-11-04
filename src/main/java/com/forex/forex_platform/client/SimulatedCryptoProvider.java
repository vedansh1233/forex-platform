package com.forex.forex_platform.client;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Offline crypto prices for tests and local development ({@code app.crypto.provider=simulated}),
 * and the labelled fallback when the live feed is down. Daily closes drift deterministically
 * (crypto-sized, roughly ±15%), and the live price adds small intraday waves that change every
 * few seconds, so the ticker visibly moves.
 */
public class SimulatedCryptoProvider implements CryptoPriceProvider {

    /** Approximate USD price levels. */
    static final Map<String, BigDecimal> USD_PRICES = Map.of(
            "BTC", new BigDecimal("65000"),
            "ETH", new BigDecimal("3200"),
            "SOL", new BigDecimal("150"),
            "XRP", new BigDecimal("0.55"),
            "ADA", new BigDecimal("0.45")
    );

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);

    private final Clock clock;

    public SimulatedCryptoProvider() {
        this(Clock.systemUTC());
    }

    public SimulatedCryptoProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String source() {
        return "SIMULATED";
    }

    @Override
    public Map<String, Ticker> tickers(List<String> symbols) {
        Instant now = clock.instant();
        Map<String, Ticker> result = new LinkedHashMap<>();
        for (String symbol : symbols) {
            result.put(symbol, new Ticker(priceAt(symbol, now), priceAt(symbol, now.minusSeconds(86_400))));
        }
        return result;
    }

    @Override
    public SortedMap<LocalDate, BigDecimal> dailyCloses(String symbol, LocalDate from, LocalDate to) {
        SortedMap<LocalDate, BigDecimal> result = new TreeMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            result.put(day, scale(level(symbol).multiply(BigDecimal.valueOf(dayDrift(symbol, day.toEpochDay())), MC)));
        }
        return result;
    }

    /** Price at an instant: the day's drift, interpolated, plus intraday waves in 5-second steps. */
    private BigDecimal priceAt(String symbol, Instant instant) {
        long seconds = instant.getEpochSecond();
        double dayFraction = seconds / 86_400.0;
        double step = Math.floorDiv(seconds, 5);
        double phase = phase(symbol);
        double intraday = 1
                + 0.004 * Math.sin(step / 37.0 + phase)
                + 0.002 * Math.sin(step / 9.0 + phase * 1.9)
                + 0.001 * Math.sin(step / 2.3 + phase * 2.7);
        BigDecimal value = level(symbol)
                .multiply(BigDecimal.valueOf(dayDrift(symbol, dayFraction) * intraday), MC);
        return scale(value);
    }

    private static BigDecimal level(String symbol) {
        BigDecimal level = USD_PRICES.get(symbol);
        if (level == null) {
            throw new RatesProvider.RatesUnavailableException("No simulated price for " + symbol, null);
        }
        return level;
    }

    private static double phase(String symbol) {
        return Math.floorMod(symbol.hashCode(), 997) / 997.0 * 2 * Math.PI;
    }

    /** Smooth multi-wave drift; {@code t} is in days and may be fractional. */
    private static double dayDrift(String symbol, double t) {
        double phase = phase(symbol);
        return 1
                + 0.08 * Math.sin(t / 61.0 + phase)
                + 0.04 * Math.sin(t / 19.0 + phase * 1.7)
                + 0.02 * Math.sin(t / 6.1 + phase * 2.3)
                + 0.01 * Math.sin(t / 2.1 + phase * 3.1);
    }

    /** Enough decimals for cheap coins (XRP, ADA) without spurious precision on BTC. */
    private static BigDecimal scale(BigDecimal price) {
        int decimals = price.compareTo(BigDecimal.TEN) >= 0 ? 2 : 6;
        return price.setScale(decimals, RoundingMode.HALF_EVEN);
    }
}
