package com.forex.forex_platform.market;

import java.math.BigDecimal;

/**
 * Something the wallet can hold and trade.
 *
 * @param decimals how many decimal places amounts of this asset may have (2 for currencies, 8 for crypto)
 */
public record Asset(String code, String name, AssetType type, int decimals) {

    /** The smallest amount that can be held or traded: 0.01 for currencies, 0.00000001 for crypto. */
    public BigDecimal minAmount() {
        return BigDecimal.ONE.movePointLeft(decimals);
    }

    public boolean isCrypto() {
        return type == AssetType.CRYPTO;
    }
}
