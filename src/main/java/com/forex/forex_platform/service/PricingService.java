package com.forex.forex_platform.service;

import com.forex.forex_platform.exception.ServiceUnavailableException;
import com.forex.forex_platform.market.Asset;
import com.forex.forex_platform.market.Assets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * One price for any pair of assets. Currency pairs use ECB rates directly (exactly what
 * {@code GET /api/rates} shows); any pair involving crypto goes through USD:
 * {@code rate(from, to) = usdPrice(from) / usdPrice(to)}.
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    /** Rates are stored with 18 decimal places (e.g. JPY -> BTC is about 0.00000006). */
    public static final int RATE_SCALE = 18;
    private static final MathContext MC = new MathContext(24, RoundingMode.HALF_EVEN);

    private final RatesService ratesService;
    private final CryptoPriceService cryptoPriceService;

    /** USD value of 1 unit of the asset. */
    public BigDecimal usdPrice(String code) {
        Asset asset = Assets.require(code);
        if (asset.code().equals(Assets.BASE)) {
            return BigDecimal.ONE;
        }
        if (asset.isCrypto()) {
            return cryptoPriceService.usdPrice(asset.code());
        }
        BigDecimal perUsd = ratesService.latest(Assets.BASE).rates().get(asset.code());
        if (perUsd == null || perUsd.signum() <= 0) {
            throw new ServiceUnavailableException("No current rate for " + asset.code());
        }
        return BigDecimal.ONE.divide(perUsd, MC);
    }

    /** Units of {@code to} per 1 {@code from}. */
    public BigDecimal rate(String from, String to) {
        Asset fromAsset = Assets.require(from);
        Asset toAsset = Assets.require(to);
        if (fromAsset.equals(toAsset)) {
            return BigDecimal.ONE;
        }
        if (!fromAsset.isCrypto() && !toAsset.isCrypto()) {
            return ratesService.rate(fromAsset.code(), toAsset.code());
        }
        return usdPrice(fromAsset.code())
                .divide(usdPrice(toAsset.code()), MC)
                .setScale(RATE_SCALE, RoundingMode.HALF_EVEN)
                .stripTrailingZeros();
    }

    /** Value of {@code amount} of an asset in USD, rounded to cents. */
    public BigDecimal usdValue(String code, BigDecimal amount) {
        return amount.multiply(usdPrice(code), MC).setScale(2, RoundingMode.HALF_EVEN);
    }
}
