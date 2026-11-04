package com.forex.forex_platform.service;

import com.forex.forex_platform.client.RatesProvider;
import com.forex.forex_platform.dto.RatesResponse;
import com.forex.forex_platform.exception.BadRequestException;
import com.forex.forex_platform.exception.ServiceUnavailableException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests of RatesService caching and fallback, with a controllable fake provider and clock. */
class RatesServiceTest {

    private static final Instant START = Instant.parse("2026-11-04T10:00:00Z");

    /** Live-like provider whose availability the test can toggle; returns 1.10 for every symbol. */
    static class FakeProvider implements RatesProvider {
        final AtomicBoolean up = new AtomicBoolean(true);
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String source() {
            return "ECB";
        }

        @Override
        public SortedMap<LocalDate, Map<String, BigDecimal>> timeSeries(String base, Iterable<String> symbols,
                                                                       LocalDate from, LocalDate to) {
            calls.incrementAndGet();
            if (!up.get()) {
                throw new RatesUnavailableException("down", null);
            }
            Map<String, BigDecimal> rates = new LinkedHashMap<>();
            symbols.forEach(s -> rates.put(s, new BigDecimal("1.10")));
            SortedMap<LocalDate, Map<String, BigDecimal>> series = new TreeMap<>();
            series.put(to.minusDays(1), rates);
            return series;
        }
    }

    /** Clock the test can move forward. */
    static class MutableClock extends Clock {
        Instant now = START;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private final FakeProvider provider = new FakeProvider();
    private final MutableClock clock = new MutableClock();

    @Test
    void cachesWithinTtl() {
        RatesService service = new RatesService(provider, true, clock);

        service.latest("USD");
        service.latest("usd");
        assertThat(provider.calls.get()).isEqualTo(1);

        clock.advance(RatesService.LATEST_TTL.plusSeconds(1));
        service.latest("USD");
        assertThat(provider.calls.get()).isEqualTo(2);
    }

    @Test
    void servesStaleLiveDataWhenProviderFails() {
        RatesService service = new RatesService(provider, true, clock);
        service.latest("USD");

        provider.up.set(false);
        clock.advance(Duration.ofHours(5));

        RatesResponse response = service.latest("USD");
        assertThat(response.source()).isEqualTo("ECB");
        assertThat(response.rates().get("EUR")).isEqualByComparingTo("1.10");
    }

    @Test
    void fallsBackToLabelledSimulatedRatesAndRetriesLiveSoon() {
        provider.up.set(false);
        RatesService service = new RatesService(provider, true, clock);

        RatesResponse simulated = service.latest("USD");
        assertThat(simulated.source()).isEqualTo("SIMULATED");
        assertThat(simulated.rates()).containsKeys("EUR", "GBP", "JPY");

        // Live source recovers: after the short fallback TTL, live data replaces simulated data
        provider.up.set(true);
        clock.advance(RatesService.FALLBACK_TTL.plusSeconds(1));
        assertThat(service.latest("USD").source()).isEqualTo("ECB");
    }

    @Test
    void returns503WhenFallbackDisabledAndNothingCached() {
        provider.up.set(false);
        RatesService service = new RatesService(provider, false, clock);

        assertThatThrownBy(() -> service.latest("USD")).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void validatesCurrencies() {
        RatesService service = new RatesService(provider, true, clock);

        assertThatThrownBy(() -> service.latest("BTC")).isInstanceOf(BadRequestException.class);
        assertThat(service.rate("EUR", "eur")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(provider.calls.get()).isZero();
    }
}
