package com.forex.forex_platform.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Convert {@code amount} of {@code from} into {@code to}. Assets are currency or crypto codes.
 * The per-asset decimal limit (2 for currencies, 8 for crypto) is checked by the service.
 */
public record ConvertRequest(

        @NotBlank(message = "Source asset is required")
        String from,

        @NotBlank(message = "Target asset is required")
        String to,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0", inclusive = false, message = "Amount must be greater than 0")
        @Digits(integer = 15, fraction = 8, message = "Amount can have at most 8 decimal places")
        BigDecimal amount
) {
}
