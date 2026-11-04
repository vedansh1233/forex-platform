package com.forex.forex_platform.controller;

import com.forex.forex_platform.dto.RateHistoryResponse;
import com.forex.forex_platform.dto.RatesResponse;
import com.forex.forex_platform.service.RatesService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public market data (no login needed), sourced from ECB reference rates. */
@RestController
@RequestMapping("/api/rates")
@RequiredArgsConstructor
@Tag(name = "Rates")
@SecurityRequirements
public class RatesController {

    private final RatesService ratesService;

    @GetMapping("/currencies")
    public List<String> currencies() {
        return RatesService.SUPPORTED_CURRENCIES;
    }

    @GetMapping
    public RatesResponse latest(@RequestParam(defaultValue = "USD") String base) {
        return ratesService.latest(base);
    }

    @GetMapping("/history")
    public RateHistoryResponse history(@RequestParam String base,
                                       @RequestParam String quote,
                                       @RequestParam(defaultValue = "30") int days) {
        return ratesService.history(base, quote, days);
    }
}
