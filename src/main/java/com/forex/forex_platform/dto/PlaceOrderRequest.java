package com.forex.forex_platform.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Limit order: convert {@code amount} of {@code from} into {@code to} once 1 {@code from} buys at
 * least {@code targetRate} units of {@code to}.
 */
public record PlaceOrderRequest(

        @NotBlank(message = "Source asset is required")
        String from,

        @NotBlank(message = "Target asset is required")
        String to,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0", inclusive = false, message = "Amount must be greater than 0")
        @Digits(integer = 15, fraction = 8, message = "Amount can have at most 8 decimal places")
        BigDecimal amount,

        @NotNull(message = "Target rate is required")
        @DecimalMin(value = "0", inclusive = false, message = "Target rate must be greater than 0")
        @Digits(integer = 20, fraction = 18, message = "Target rate can have at most 18 decimal places")
        BigDecimal targetRate
) {
}
