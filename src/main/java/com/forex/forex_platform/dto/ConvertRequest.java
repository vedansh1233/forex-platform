package com.forex.forex_platform.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ConvertRequest(

        @NotBlank(message = "Source currency is required")
        String from,

        @NotBlank(message = "Target currency is required")
        String to,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        @Digits(integer = 13, fraction = 2, message = "Amount can have at most 2 decimal places")
        BigDecimal amount
) {
}
