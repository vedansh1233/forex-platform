package com.forex.forex_platform.config;

import com.forex.forex_platform.client.FixedRatesProvider;
import com.forex.forex_platform.client.RatesProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the rates provider with {@code app.rates.provider}: "frankfurter" (default,
 * {@link com.forex.forex_platform.client.FrankfurterRatesProvider}) or "fixed" (offline).
 */
@Configuration
public class RatesConfig {

    @Bean
    @ConditionalOnProperty(name = "app.rates.provider", havingValue = "fixed")
    public RatesProvider fixedRatesProvider() {
        return new FixedRatesProvider();
    }
}
