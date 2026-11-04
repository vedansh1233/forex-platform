package com.forex.forex_platform.service;

import com.forex.forex_platform.client.FixedRatesProvider;
import com.forex.forex_platform.client.RatesProvider;
import com.forex.forex_platform.client.RatesProvider.RatesUnavailableException;
import com.forex.forex_platform.dto.RateHistoryResponse;
import com.forex.forex_platform.dto.RatesResponse;
import com.forex.forex_platform.exception.BadRequestException;
import com.forex.forex_platform.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Exchange rates with validation and an in-memory cache. When the live provider fails:
 * <ol>
 *   <li>the last cached value is served, even if expired (rates change once per business day);</li>
 *   <li>otherwise, if enabled, simulated rates are served, labelled {@code source=SIMULATED}
 *       and cached only briefly so the live source is retried soon;</li>
 *   <li>otherwise a 503 is returned.</li>
 * </ol>
 */
@Slf4j
@Service
public class RatesService {

    /** Currencies the platform trades: the major FX pairs. */
    public static final List<String> SUPPORTED_CURRENCIES =
            List.of("USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD");

    public static final int MIN_HISTORY_DAYS = 7;
    public static final int MAX_HISTORY_DAYS = 365;

    static final Duration LATEST_TTL = Duration.ofMinutes(15);
    static final Duration HISTORY_TTL = Duration.ofHours(1);
    static final Duration FALLBACK_TTL = Duration.ofMinutes(5);
    /** Look-back window for "latest", wide enough to cover weekends and holidays. */
    private static final int LATEST_LOOKBACK_DAYS = 10;

    private final RatesProvider ratesProvider;
    private final RatesProvider fallbackProvider;
    private final Clock clock;

    private final Map<String, CacheEntry<RatesResponse>> latestCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<RateHistoryResponse>> historyCache = new ConcurrentHashMap<>();

    @Autowired
    public RatesService(RatesProvider ratesProvider,
                        @Value("${app.rates.fallback-to-simulated:true}") boolean fallbackToSimulated) {
        this(ratesProvider, fallbackToSimulated, Clock.systemUTC());
    }

    RatesService(RatesProvider ratesProvider, boolean fallbackToSimulated, Clock clock) {
        this.ratesProvider = ratesProvider;
        this.fallbackProvider = fallbackToSimulated ? new FixedRatesProvider() : null;
        this.clock = clock;
    }

    /** Upper-cases a currency code and checks it is supported (400 otherwise). */
    public static String requireSupported(String currency) {
        String code = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_CURRENCIES.contains(code)) {
            throw new BadRequestException(
                    "Unsupported currency '" + currency + "'. Supported: " + String.join(", ", SUPPORTED_CURRENCIES));
        }
        return code;
    }

    /** Latest rates for 1 unit of {@code base} against every other supported currency. */
    public RatesResponse latest(String base) {
        String code = requireSupported(base);
        return cached(latestCache, code, LATEST_TTL, provider -> fetchLatest(provider, code));
    }

    /** Units of {@code to} per 1 {@code from}. */
    public BigDecimal rate(String from, String to) {
        String fromCode = requireSupported(from);
        String toCode = requireSupported(to);
        if (fromCode.equals(toCode)) {
            return BigDecimal.ONE;
        }
        BigDecimal rate = latest(fromCode).rates().get(toCode);
        if (rate == null) {
            throw new ServiceUnavailableException("No current rate for " + fromCode + "/" + toCode);
        }
        return rate;
    }

    public RateHistoryResponse history(String base, String quote, int days) {
        String baseCode = requireSupported(base);
        String quoteCode = requireSupported(quote);
        if (baseCode.equals(quoteCode)) {
            throw new BadRequestException("Base and quote currencies must differ");
        }
        if (days < MIN_HISTORY_DAYS || days > MAX_HISTORY_DAYS) {
            throw new BadRequestException(
                    "days must be between " + MIN_HISTORY_DAYS + " and " + MAX_HISTORY_DAYS);
        }
        String key = baseCode + "/" + quoteCode + "/" + days;
        return cached(historyCache, key, HISTORY_TTL, provider -> fetchHistory(provider, baseCode, quoteCode, days));
    }

    // --- fetching ---

    private RatesResponse fetchLatest(RatesProvider provider, String base) {
        List<String> symbols = SUPPORTED_CURRENCIES.stream().filter(c -> !c.equals(base)).toList();
        LocalDate today = LocalDate.now(clock);
        SortedMap<LocalDate, Map<String, BigDecimal>> series =
                provider.timeSeries(base, symbols, today.minusDays(LATEST_LOOKBACK_DAYS), today);
        if (series.isEmpty()) {
            throw new RatesUnavailableException("No recent rates for " + base, null);
        }
        LocalDate date = series.lastKey();
        Map<String, BigDecimal> raw = series.get(date);

        // Keep a stable, predictable order for the client
        Map<String, BigDecimal> ordered = new LinkedHashMap<>();
        for (String symbol : symbols) {
            BigDecimal rate = raw.get(symbol);
            if (rate != null) {
                ordered.put(symbol, rate);
            }
        }
        return new RatesResponse(base, date, provider.source(), ordered);
    }

    private RateHistoryResponse fetchHistory(RatesProvider provider, String base, String quote, int days) {
        LocalDate today = LocalDate.now(clock);
        SortedMap<LocalDate, Map<String, BigDecimal>> series =
                provider.timeSeries(base, List.of(quote), today.minusDays(days), today);
        List<RateHistoryResponse.Point> points = series.entrySet().stream()
                .filter(e -> e.getValue().get(quote) != null)
                .map(e -> new RateHistoryResponse.Point(e.getKey(), e.getValue().get(quote)))
                .toList();
        return new RateHistoryResponse(base, quote, provider.source(), points);
    }

    // --- caching and fallback ---

    private <T> T cached(Map<String, CacheEntry<T>> cache, String key, Duration ttl,
                         Function<RatesProvider, T> loader) {
        Instant now = clock.instant();
        CacheEntry<T> entry = cache.get(key);
        if (entry != null && entry.expiresAt().isAfter(now)) {
            return entry.value();
        }
        try {
            T value = loader.apply(ratesProvider);
            cache.put(key, new CacheEntry<>(value, now.plus(ttl), false));
            return value;
        } catch (RatesUnavailableException e) {
            // Prefer real (if stale) data over simulated data
            if (entry != null && !entry.simulated()) {
                log.warn("Rates provider failed for {}, serving stale cached value: {}", key, e.getMessage());
                return entry.value();
            }
            if (fallbackProvider != null) {
                log.warn("Rates provider failed for {}, serving simulated rates: {}", key, e.getMessage());
                T value = loader.apply(fallbackProvider);
                cache.put(key, new CacheEntry<>(value, now.plus(FALLBACK_TTL), true));
                return value;
            }
            log.error("Rates provider failed for {} and nothing is cached", key, e);
            throw new ServiceUnavailableException("Exchange rates are temporarily unavailable. Please try again shortly.");
        }
    }

    private record CacheEntry<T>(T value, Instant expiresAt, boolean simulated) {
    }
}
