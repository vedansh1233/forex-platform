package com.forex.forex_platform.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.forex.forex_platform.client.RatesProvider.RatesUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Live crypto prices from Coinbase Exchange's public market data API (free, no API key).
 * Uses {@code /products/{SYMBOL}-USD/stats} for the current and 24-hour-ago price, and
 * {@code /products/{SYMBOL}-USD/candles} for daily history.
 */
@Component
@ConditionalOnProperty(name = "app.crypto.provider", havingValue = "coinbase", matchIfMissing = true)
public class CoinbaseCryptoProvider implements CryptoPriceProvider {

    /** Coinbase returns at most 300 candles per request. */
    private static final int MAX_CANDLES = 300;
    private static final int DAY_SECONDS = 86_400;

    private final RestClient restClient;

    public CoinbaseCryptoProvider(@Value("${app.crypto.coinbase-url:https://api.exchange.coinbase.com}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(4));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                // Coinbase rejects requests without a User-Agent
                .defaultHeader(HttpHeaders.USER_AGENT, "fx-desk-demo")
                .build();
    }

    @Override
    public String source() {
        return "COINBASE";
    }

    @Override
    public Map<String, Ticker> tickers(List<String> symbols) {
        // One request per product; virtual threads fetch them in parallel
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<Ticker>> futures = new LinkedHashMap<>();
            for (String symbol : symbols) {
                futures.put(symbol, executor.submit(() -> fetchTicker(symbol)));
            }
            Map<String, Ticker> result = new LinkedHashMap<>();
            for (Map.Entry<String, Future<Ticker>> entry : futures.entrySet()) {
                result.put(entry.getKey(), entry.getValue().get());
            }
            return result;
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RatesUnavailableException rue
                    ? rue
                    : new RatesUnavailableException("Coinbase request failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RatesUnavailableException("Interrupted while fetching crypto prices", e);
        }
    }

    private Ticker fetchTicker(String symbol) {
        JsonNode stats;
        try {
            stats = restClient.get()
                    .uri("/products/{product}/stats", symbol + "-USD")
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new RatesUnavailableException("Coinbase stats request failed for " + symbol, e);
        }
        if (stats == null || !stats.hasNonNull("last") || !stats.hasNonNull("open")) {
            throw new RatesUnavailableException("Coinbase returned no price for " + symbol, null);
        }
        return new Ticker(new BigDecimal(stats.get("last").asText()), new BigDecimal(stats.get("open").asText()));
    }

    @Override
    public SortedMap<LocalDate, BigDecimal> dailyCloses(String symbol, LocalDate from, LocalDate to) {
        SortedMap<LocalDate, BigDecimal> closes = new TreeMap<>();
        Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();

        // Walk backwards in windows of at most 300 days
        while (end.isAfter(start)) {
            Instant windowStart = end.minusSeconds((long) MAX_CANDLES * DAY_SECONDS);
            if (windowStart.isBefore(start)) {
                windowStart = start;
            }
            JsonNode candles;
            try {
                candles = restClient.get()
                        .uri("/products/{product}/candles?granularity={granularity}&start={start}&end={end}",
                                symbol + "-USD", DAY_SECONDS, windowStart.toString(), end.toString())
                        .retrieve()
                        .body(JsonNode.class);
            } catch (RestClientException e) {
                throw new RatesUnavailableException("Coinbase candles request failed for " + symbol, e);
            }
            if (candles == null || !candles.isArray()) {
                throw new RatesUnavailableException("Coinbase returned no candles for " + symbol, null);
            }
            // Each candle: [time, low, high, open, close, volume]
            for (JsonNode candle : candles) {
                LocalDate day = LocalDate.ofInstant(Instant.ofEpochSecond(candle.get(0).asLong()), ZoneOffset.UTC);
                if (!day.isBefore(from) && !day.isAfter(to)) {
                    closes.put(day, new BigDecimal(candle.get(4).asText()));
                }
            }
            end = windowStart;
        }
        return closes;
    }
}
