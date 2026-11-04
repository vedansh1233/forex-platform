package com.forex.forex_platform.service;

import com.forex.forex_platform.client.CryptoPriceProvider;
import com.forex.forex_platform.client.CryptoPriceProvider.Ticker;
import com.forex.forex_platform.client.RatesProvider.RatesUnavailableException;
import com.forex.forex_platform.client.SimulatedCryptoProvider;
import com.forex.forex_platform.dto.CryptoPricesResponse;
import com.forex.forex_platform.dto.CryptoPricesResponse.CryptoPrice;
import com.forex.forex_platform.dto.RateHistoryResponse;
import com.forex.forex_platform.event.CryptoPricesUpdatedEvent;
import com.forex.forex_platform.exception.BadRequestException;
import com.forex.forex_platform.exception.ServiceUnavailableException;
import com.forex.forex_platform.market.Asset;
import com.forex.forex_platform.market.Assets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crypto prices in USD. A background job calls {@link #refresh()} every few seconds; each refresh is
 * published as a {@link CryptoPricesUpdatedEvent} for WebSocket clients. When the live provider fails:
 * <ol>
 *   <li>the last live snapshot keeps being served while it is younger than {@link #MAX_LIVE_AGE};</li>
 *   <li>after that, if enabled, simulated prices are served, labelled {@code source=SIMULATED};</li>
 *   <li>otherwise a 503 is returned.</li>
 * </ol>
 */
@Slf4j
@Service
public class CryptoPriceService {

    /** How long live prices may be served after the feed stops answering. */
    static final Duration MAX_LIVE_AGE = Duration.ofMinutes(2);
    /** If nothing refreshed prices for this long (e.g. jobs disabled), refresh on read. */
    static final Duration REFRESH_ON_READ_AFTER = Duration.ofSeconds(30);
    static final Duration HISTORY_TTL = Duration.ofHours(1);
    static final Duration FALLBACK_HISTORY_TTL = Duration.ofMinutes(5);

    private static final MathContext MC = MathContext.DECIMAL64;

    private final CryptoPriceProvider provider;
    private final CryptoPriceProvider fallbackProvider;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    private volatile Snapshot snapshot;
    private final Map<String, HistoryEntry> historyCache = new ConcurrentHashMap<>();

    @Autowired
    public CryptoPriceService(CryptoPriceProvider provider,
                              @Value("${app.rates.fallback-to-simulated:true}") boolean fallbackToSimulated,
                              ApplicationEventPublisher events) {
        this(provider, fallbackToSimulated ? new SimulatedCryptoProvider() : null, events, Clock.systemUTC());
    }

    CryptoPriceService(CryptoPriceProvider provider, CryptoPriceProvider fallbackProvider,
                       ApplicationEventPublisher events, Clock clock) {
        this.provider = provider;
        this.fallbackProvider = fallbackProvider;
        this.events = events;
        this.clock = clock;
    }

    /** Latest prices; fetches them first if no recent refresh has happened. */
    public CryptoPricesResponse latest() {
        Snapshot current = snapshot;
        if (current == null || current.fetchedAt().plus(REFRESH_ON_READ_AFTER).isBefore(clock.instant())) {
            return refresh();
        }
        return current.prices();
    }

    /** USD price of one crypto asset. */
    public BigDecimal usdPrice(String symbol) {
        String code = requireCrypto(symbol).code();
        return latest().prices().stream()
                .filter(p -> p.symbol().equals(code))
                .findFirst()
                .map(CryptoPrice::priceUsd)
                .orElseThrow(() -> new ServiceUnavailableException("No current price for " + code));
    }

    /** Fetches fresh prices (with fallback) and publishes them. */
    public synchronized CryptoPricesResponse refresh() {
        Instant now = clock.instant();
        Snapshot next;
        try {
            next = new Snapshot(build(provider, now), now, now);
        } catch (RatesUnavailableException e) {
            Snapshot current = snapshot;
            if (current != null && current.lastLiveAt() != null
                    && current.lastLiveAt().plus(MAX_LIVE_AGE).isAfter(now)) {
                log.debug("Crypto feed failed, keeping prices from {}: {}", current.lastLiveAt(), e.getMessage());
                // Keep the live prices but mark this attempt, so reads don't retry on every request
                snapshot = new Snapshot(current.prices(), now, current.lastLiveAt());
                return current.prices();
            }
            if (fallbackProvider == null) {
                log.error("Crypto feed failed and no recent prices are available", e);
                throw new ServiceUnavailableException("Crypto prices are temporarily unavailable. Please try again shortly.");
            }
            log.warn("Crypto feed failed, serving simulated prices: {}", e.getMessage());
            next = new Snapshot(build(fallbackProvider, now), now, current == null ? null : current.lastLiveAt());
        }
        snapshot = next;
        events.publishEvent(new CryptoPricesUpdatedEvent(next.prices()));
        return next.prices();
    }

    /** Daily USD closes for one crypto asset over the last {@code days} days. */
    public RateHistoryResponse history(String symbol, int days) {
        Asset asset = requireCrypto(symbol);
        if (days < RatesService.MIN_HISTORY_DAYS || days > RatesService.MAX_HISTORY_DAYS) {
            throw new BadRequestException(
                    "days must be between " + RatesService.MIN_HISTORY_DAYS + " and " + RatesService.MAX_HISTORY_DAYS);
        }
        String key = asset.code() + "/" + days;
        Instant now = clock.instant();
        HistoryEntry entry = historyCache.get(key);
        if (entry != null && entry.expiresAt().isAfter(now)) {
            return entry.value();
        }
        try {
            RateHistoryResponse value = fetchHistory(provider, asset.code(), days);
            historyCache.put(key, new HistoryEntry(value, now.plus(HISTORY_TTL), false));
            return value;
        } catch (RatesUnavailableException e) {
            if (entry != null && !entry.simulated()) {
                return entry.value();
            }
            if (fallbackProvider == null) {
                throw new ServiceUnavailableException("Crypto prices are temporarily unavailable. Please try again shortly.");
            }
            log.warn("Crypto history failed for {}, serving simulated history: {}", key, e.getMessage());
            RateHistoryResponse value = fetchHistory(fallbackProvider, asset.code(), days);
            historyCache.put(key, new HistoryEntry(value, now.plus(FALLBACK_HISTORY_TTL), true));
            return value;
        }
    }

    // --- helpers ---

    private static Asset requireCrypto(String symbol) {
        Asset asset = Assets.require(symbol);
        if (!asset.isCrypto()) {
            throw new BadRequestException(asset.code() + " is not a crypto asset");
        }
        return asset;
    }

    private CryptoPricesResponse build(CryptoPriceProvider source, Instant now) {
        Map<String, Ticker> tickers = source.tickers(Assets.CRYPTO_CODES);
        List<CryptoPrice> prices = new ArrayList<>();
        for (Asset asset : Assets.CRYPTO) {
            Ticker ticker = tickers.get(asset.code());
            if (ticker == null || ticker.price() == null || ticker.price().signum() <= 0) {
                throw new RatesUnavailableException("Missing price for " + asset.code(), null);
            }
            BigDecimal change = ticker.open24h() == null || ticker.open24h().signum() == 0
                    ? BigDecimal.ZERO
                    : ticker.price().divide(ticker.open24h(), MC).subtract(BigDecimal.ONE)
                    .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_EVEN);
            prices.add(new CryptoPrice(asset.code(), asset.name(), ticker.price(), change));
        }
        return new CryptoPricesResponse(source.source(), now, prices);
    }

    private RateHistoryResponse fetchHistory(CryptoPriceProvider source, String symbol, int days) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        SortedMap<LocalDate, BigDecimal> closes = source.dailyCloses(symbol, today.minusDays(days), today);
        List<RateHistoryResponse.Point> points = closes.entrySet().stream()
                .map(e -> new RateHistoryResponse.Point(e.getKey(), e.getValue()))
                .toList();
        return new RateHistoryResponse(symbol, Assets.BASE, source.source(), points);
    }

    /**
     * @param fetchedAt  last refresh attempt
     * @param lastLiveAt last time the live provider answered; null if it never has
     */
    private record Snapshot(CryptoPricesResponse prices, Instant fetchedAt, Instant lastLiveAt) {
    }

    private record HistoryEntry(RateHistoryResponse value, Instant expiresAt, boolean simulated) {
    }
}
