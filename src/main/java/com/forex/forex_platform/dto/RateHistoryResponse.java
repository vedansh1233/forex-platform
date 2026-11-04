package com.forex.forex_platform.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Daily history of one currency pair, oldest first. {@code source} as in {@link RatesResponse}. */
public record RateHistoryResponse(
        String base,
        String quote,
        String source,
        List<Point> points
) {
    public record Point(LocalDate date, BigDecimal rate) {
    }
}
