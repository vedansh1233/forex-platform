package com.forex.forex_platform.dto;

import com.forex.forex_platform.entity.LimitOrder;
import com.forex.forex_platform.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * @param currentRate    the latest rate for the pair, to show how far an open order is from its
 *                       target; null if prices are unavailable or the order is closed
 * @param fillRate       rate the order executed at (FILLED only)
 * @param receivedAmount amount of {@code to} received (FILLED only)
 * @param transactionId  the ledger entry created by the fill (FILLED only)
 * @param failureReason  why it couldn't execute (FAILED only)
 */
public record OrderResponse(
        Long id,
        String from,
        String to,
        BigDecimal amount,
        BigDecimal targetRate,
        OrderStatus status,
        LocalDateTime createdAt,
        LocalDateTime closedAt,
        BigDecimal currentRate,
        BigDecimal fillRate,
        BigDecimal receivedAmount,
        Long transactionId,
        String failureReason
) {
    public static OrderResponse from(LimitOrder order, BigDecimal currentRate) {
        return new OrderResponse(
                order.getId(),
                order.getFromCurrency(),
                order.getToCurrency(),
                order.getAmount().stripTrailingZeros(),
                order.getTargetRate().stripTrailingZeros(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getClosedAt(),
                currentRate,
                order.getFillRate() == null ? null : order.getFillRate().stripTrailingZeros(),
                order.getReceivedAmount() == null ? null : order.getReceivedAmount().stripTrailingZeros(),
                order.getTransactionId(),
                order.getFailureReason()
        );
    }
}
