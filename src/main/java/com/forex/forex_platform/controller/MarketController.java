package com.forex.forex_platform.controller;

import com.forex.forex_platform.dto.AssetResponse;
import com.forex.forex_platform.dto.CryptoPricesResponse;
import com.forex.forex_platform.dto.QuoteResponse;
import com.forex.forex_platform.dto.RateHistoryResponse;
import com.forex.forex_platform.market.Asset;
import com.forex.forex_platform.market.Assets;
import com.forex.forex_platform.service.CryptoPriceService;
import com.forex.forex_platform.service.PricingService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public market data across currencies and crypto (no login needed). Live crypto prices are also
 * pushed over the {@code /ws/prices} WebSocket.
 */
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
@Tag(name = "Market")
@SecurityRequirements
public class MarketController {

    private final PricingService pricingService;
    private final CryptoPriceService cryptoPriceService;

    /** Every tradable asset: 8 currencies, then 5 cryptocurrencies. */
    @GetMapping("/assets")
    public List<AssetResponse> assets() {
        return Assets.ALL.stream().map(AssetResponse::from).toList();
    }

    /** Current rate for any pair, e.g. BTC -> EUR. */
    @GetMapping("/quote")
    public QuoteResponse quote(@RequestParam String from, @RequestParam String to) {
        Asset fromAsset = Assets.require(from);
        Asset toAsset = Assets.require(to);
        return new QuoteResponse(fromAsset.code(), toAsset.code(), pricingService.rate(fromAsset.code(), toAsset.code()));
    }

    /** Latest crypto prices in USD with 24-hour change. */
    @GetMapping("/crypto")
    public CryptoPricesResponse crypto() {
        return cryptoPriceService.latest();
    }

    /** Daily USD closes for one crypto asset; days 7–365. */
    @GetMapping("/crypto/history")
    public RateHistoryResponse cryptoHistory(@RequestParam String symbol,
                                             @RequestParam(defaultValue = "30") int days) {
        return cryptoPriceService.history(symbol, days);
    }
}
