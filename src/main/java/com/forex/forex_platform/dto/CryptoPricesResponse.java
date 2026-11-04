package com.forex.forex_platform.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Latest crypto prices. Also the payload pushed over the {@code /ws/prices} WebSocket.
 *
 * @param source    "COINBASE" (live) or "SIMULATED" (fallback)
 * @param updatedAt when these prices were fetched
 */
public record CryptoPricesResponse(String source, Instant updatedAt, List<CryptoPrice> prices) {

    /** @param change24h percent change over the last 24 hours, e.g. 2.15 */
    public record CryptoPrice(String symbol, String name, BigDecimal priceUsd, BigDecimal change24h) {
    }
}
