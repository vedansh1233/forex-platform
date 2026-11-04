package com.forex.forex_platform.config;

import com.forex.forex_platform.client.CryptoPriceProvider;
import com.forex.forex_platform.client.FixedRatesProvider;
import com.forex.forex_platform.client.RatesProvider;
import com.forex.forex_platform.client.SimulatedCryptoProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the market data providers.
 * <ul>
 *   <li>{@code app.rates.provider}: "frankfurter" (default, live ECB rates) or "fixed" (offline).</li>
 *   <li>{@code app.crypto.provider}: "coinbase" (default, live prices) or "simulated" (offline).</li>
 * </ul>
 * The live providers are {@code @Component}s guarded by the same properties.
 */
@Configuration
public class RatesConfig {

    @Bean
    @ConditionalOnProperty(name = "app.rates.provider", havingValue = "fixed")
    public RatesProvider fixedRatesProvider() {
        return new FixedRatesProvider();
    }

    @Bean
    @ConditionalOnProperty(name = "app.crypto.provider", havingValue = "simulated")
    public CryptoPriceProvider simulatedCryptoProvider() {
        return new SimulatedCryptoProvider();
    }
}
