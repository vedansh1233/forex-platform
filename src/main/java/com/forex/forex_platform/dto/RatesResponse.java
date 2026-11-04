package com.forex.forex_platform.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Latest rates: units of each currency per 1 {@code base}. {@code date} is the
 * publication day the rates come from (business days only). {@code source} is "ECB"
 * for live reference rates or "SIMULATED" when the live source is unavailable.
 */
public record RatesResponse(
        String base,
        LocalDate date,
        String source,
        Map<String, BigDecimal> rates
) {
}
