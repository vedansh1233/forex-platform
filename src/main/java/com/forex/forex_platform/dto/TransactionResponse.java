package com.forex.forex_platform.dto;

import com.forex.forex_platform.entity.TransactionType;
import com.forex.forex_platform.entity.WalletTransaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A ledger entry. For DEPOSIT, the "from" fields and {@code rate} are null. */
public record TransactionResponse(
        Long id,
        TransactionType type,
        String fromCurrency,
        BigDecimal fromAmount,
        String toCurrency,
        BigDecimal toAmount,
        BigDecimal rate,
        LocalDateTime createdAt
) {
    public static TransactionResponse from(WalletTransaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getType(),
                tx.getFromCurrency(),
                tx.getFromAmount(),
                tx.getToCurrency(),
                tx.getToAmount(),
                tx.getRate(),
                tx.getCreatedAt()
        );
    }
}
