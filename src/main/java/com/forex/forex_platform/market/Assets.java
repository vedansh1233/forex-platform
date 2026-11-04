package com.forex.forex_platform.market;

import com.forex.forex_platform.exception.BadRequestException;
import com.forex.forex_platform.service.RatesService;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Registry of every asset the platform trades, in display order (currencies first, then crypto). */
public final class Assets {

    public static final String BASE = "USD";

    public static final List<Asset> FIAT = List.of(
            new Asset("USD", "US Dollar", AssetType.FIAT, 2),
            new Asset("EUR", "Euro", AssetType.FIAT, 2),
            new Asset("GBP", "British Pound", AssetType.FIAT, 2),
            new Asset("JPY", "Japanese Yen", AssetType.FIAT, 2),
            new Asset("CHF", "Swiss Franc", AssetType.FIAT, 2),
            new Asset("CAD", "Canadian Dollar", AssetType.FIAT, 2),
            new Asset("AUD", "Australian Dollar", AssetType.FIAT, 2),
            new Asset("NZD", "New Zealand Dollar", AssetType.FIAT, 2)
    );

    public static final List<Asset> CRYPTO = List.of(
            new Asset("BTC", "Bitcoin", AssetType.CRYPTO, 8),
            new Asset("ETH", "Ethereum", AssetType.CRYPTO, 8),
            new Asset("SOL", "Solana", AssetType.CRYPTO, 8),
            new Asset("XRP", "XRP", AssetType.CRYPTO, 8),
            new Asset("ADA", "Cardano", AssetType.CRYPTO, 8)
    );

    public static final List<Asset> ALL = Stream.concat(FIAT.stream(), CRYPTO.stream()).toList();

    public static final List<String> CRYPTO_CODES = CRYPTO.stream().map(Asset::code).toList();

    private static final Map<String, Asset> BY_CODE = new LinkedHashMap<>();

    static {
        ALL.forEach(a -> BY_CODE.put(a.code(), a));
        // The rates service owns the fiat list; keep them in step
        if (!FIAT.stream().map(Asset::code).toList().equals(RatesService.SUPPORTED_CURRENCIES)) {
            throw new IllegalStateException("Assets.FIAT must match RatesService.SUPPORTED_CURRENCIES");
        }
    }

    private Assets() {
    }

    public static Optional<Asset> find(String code) {
        return Optional.ofNullable(code == null ? null : BY_CODE.get(code.trim().toUpperCase(Locale.ROOT)));
    }

    /** Looks up an asset by code (case-insensitive), or throws a 400. */
    public static Asset require(String code) {
        return find(code).orElseThrow(() -> new BadRequestException(
                "Unsupported asset '" + code + "'. Supported: " + String.join(", ", BY_CODE.keySet())));
    }

    /** Position of an asset in display order, for sorting. */
    public static int order(String code) {
        int index = ALL.indexOf(BY_CODE.get(code));
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /** Checks an amount has no more decimal places than the asset allows (400 otherwise). */
    public static void requireValidAmount(Asset asset, BigDecimal amount) {
        if (amount.stripTrailingZeros().scale() > asset.decimals()) {
            throw new BadRequestException(asset.code() + " amounts can have at most " + asset.decimals() + " decimal places");
        }
    }
}
