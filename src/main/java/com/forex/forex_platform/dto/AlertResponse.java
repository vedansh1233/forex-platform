package com.forex.forex_platform.dto;

import com.forex.forex_platform.entity.AlertDirection;
import com.forex.forex_platform.entity.AlertStatus;
import com.forex.forex_platform.entity.PriceAlert;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * @param seen        false for a TRIGGERED alert the user hasn't acknowledged (drives the notification badge)
 * @param currentRate latest rate for the pair while ACTIVE; null otherwise or if prices are unavailable
 */
public record AlertResponse(
        Long id,
        String base,
        String quote,
        AlertDirection direction,
        BigDecimal targetRate,
        AlertStatus status,
        boolean seen,
        LocalDateTime createdAt,
        LocalDateTime triggeredAt,
        BigDecimal triggeredRate,
        BigDecimal currentRate
) {
    public static AlertResponse from(PriceAlert alert, BigDecimal currentRate) {
        return new AlertResponse(
                alert.getId(),
                alert.getBaseCurrency(),
                alert.getQuoteCurrency(),
                alert.getDirection(),
                alert.getTargetRate().stripTrailingZeros(),
                alert.getStatus(),
                alert.isSeen(),
                alert.getCreatedAt(),
                alert.getTriggeredAt(),
                alert.getTriggeredRate() == null ? null : alert.getTriggeredRate().stripTrailingZeros(),
                currentRate
        );
    }
}
