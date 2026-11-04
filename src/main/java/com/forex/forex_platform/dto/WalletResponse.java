package com.forex.forex_platform.dto;

import com.forex.forex_platform.entity.Wallet;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record WalletResponse(
        Long id,
        String fullName,
        BigDecimal balance,
        String currency,
        LocalDateTime createdAt
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getUser().getFullName(),
                wallet.getBalance(),
                wallet.getCurrency(),
                wallet.getCreatedAt()
        );
    }
}
