package com.forex.forex_platform.dto;

import java.math.BigDecimal;

/** @param rate units of {@code to} per 1 {@code from} at the latest prices */
public record QuoteResponse(String from, String to, BigDecimal rate) {
}
