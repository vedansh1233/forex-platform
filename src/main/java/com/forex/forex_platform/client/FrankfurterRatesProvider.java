package com.forex.forex_platform.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Rates from the free Frankfurter API (European Central Bank reference rates, no API key).
 * Only the time-series endpoint is used; "latest" is derived from its most recent day,
 * because the dedicated /latest endpoint has proved less reliable.
 */
@Component
@ConditionalOnProperty(name = "app.rates.provider", havingValue = "frankfurter", matchIfMissing = true)
public class FrankfurterRatesProvider implements RatesProvider {

    private final RestClient restClient;

    public FrankfurterRatesProvider(@Value("${app.rates.frankfurter-url:https://api.frankfurter.dev/v1}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        // Short timeouts: on failure RatesService falls back to cached or simulated rates
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(4));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public String source() {
        return "ECB";
    }

    @Override
    public SortedMap<LocalDate, Map<String, BigDecimal>> timeSeries(String base, Iterable<String> symbols,
                                                                   LocalDate from, LocalDate to) {
        try {
            TimeSeriesBody body = restClient.get()
                    .uri("/{from}..{to}?base={base}&symbols={symbols}",
                            from, to, base, String.join(",", symbols))
                    .retrieve()
                    .body(TimeSeriesBody.class);

            if (body == null || body.rates() == null) {
                throw new RatesUnavailableException("Empty response from rates provider", null);
            }

            SortedMap<LocalDate, Map<String, BigDecimal>> result = new TreeMap<>();
            body.rates().forEach((date, rates) -> result.put(LocalDate.parse(date), rates));
            return result;

        } catch (RestClientException e) {
            throw new RatesUnavailableException("Rates provider request failed", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TimeSeriesBody(String base, Map<String, Map<String, BigDecimal>> rates) {
    }
}
