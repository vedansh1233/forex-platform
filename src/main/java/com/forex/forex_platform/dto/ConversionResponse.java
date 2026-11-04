package com.forex.forex_platform.dto;

/** Result of a conversion: the new ledger entry and the updated wallet. */
public record ConversionResponse(
        TransactionResponse transaction,
        WalletResponse wallet
) {
}
