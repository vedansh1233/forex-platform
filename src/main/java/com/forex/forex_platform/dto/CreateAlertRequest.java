package com.forex.forex_platform.dto;

import com.forex.forex_platform.entity.AlertDirection;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Alert when 1 {@code base} is worth at least (ABOVE) or at most (BELOW) {@code targetRate} {@code quote}. */
public record CreateAlertRequest(

        @NotBlank(message = "Base asset is required")
        String base,

        @NotBlank(message = "Quote asset is required")
        String quote,

        @NotNull(message = "Direction is required (ABOVE or BELOW)")
        AlertDirection direction,

        @NotNull(message = "Target rate is required")
        @DecimalMin(value = "0", inclusive = false, message = "Target rate must be greater than 0")
        @Digits(integer = 20, fraction = 18, message = "Target rate can have at most 18 decimal places")
        BigDecimal targetRate
) {
}
