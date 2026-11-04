package com.forex.forex_platform.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @param balance        cash in the base {@code currency} (USD)
 * @param holdings       every currency with a non-zero amount, base currency first
 * @param totalValue     whole wallet valued in the base currency at the latest rates;
 *                       null if rates are temporarily unavailable
 * @param valuationDate  the rates date used for {@code totalValue}; null with it
 */
public record WalletResponse(
        Long id,
        String fullName,
        BigDecimal balance,
        String currency,
        LocalDateTime createdAt,
        List<HoldingResponse> holdings,
        BigDecimal totalValue,
        LocalDate valuationDate
) {
    /** @param valueInBase this holding valued in the wallet's base currency; null if rates are unavailable */
    public record HoldingResponse(String currency, BigDecimal amount, BigDecimal valueInBase) {
    }
}
