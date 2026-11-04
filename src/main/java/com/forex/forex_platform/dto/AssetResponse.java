package com.forex.forex_platform.dto;

import com.forex.forex_platform.market.Asset;
import com.forex.forex_platform.market.AssetType;

import java.math.BigDecimal;

/** @param decimals decimal places allowed in amounts; minAmount is the smallest tradable amount */
public record AssetResponse(String code, String name, AssetType type, int decimals, BigDecimal minAmount) {

    public static AssetResponse from(Asset asset) {
        return new AssetResponse(asset.code(), asset.name(), asset.type(), asset.decimals(), asset.minAmount());
    }
}
