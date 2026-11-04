package com.forex.forex_platform.service;

import com.forex.forex_platform.client.CryptoPriceProvider;
import com.forex.forex_platform.client.RatesProvider.RatesUnavailableException;
import com.forex.forex_platform.client.SimulatedCryptoProvider;
import com.forex.forex_platform.dto.CryptoPricesResponse;
import com.forex.forex_platform.event.CryptoPricesUpdatedEvent;
import com.forex.forex_platform.exception.BadRequestException;
import com.forex.forex_platform.exception.ServiceUnavailableException;
import com.forex.forex_platform.service.RatesServiceTest.MutableClock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Refresh, fallback and caching rules of {@link CryptoPriceService}, with a fake feed and clock. */
class CryptoPriceServiceTest {

    /** A feed that can be switched off, and counts calls. */
    static class FakeFeed implements CryptoPriceProvider {
        boolean failing;
        int tickerCalls;
        int historyCalls;
        BigDecimal btc = new BigDecimal("70000");

        @Override
        public String source() {
            return "FAKE";
        }

        @Override
        public Map<String, Ticker> tickers(List<String> symbols) {
            tickerCalls++;
            if (failing) {
                throw new RatesUnavailableException("feed down", null);
            }
            Map<String, Ticker> result = new LinkedHashMap<>();
            for (String symbol : symbols) {
                BigDecimal price = symbol.equals("BTC") ? btc : BigDecimal.TEN;
                result.put(symbol, new Ticker(price, price.multiply(new BigDecimal("0.9"))));
            }
            return result;
        }

        @Override
        public SortedMap<LocalDate, BigDecimal> dailyCloses(String symbol, LocalDate from, LocalDate to) {
            historyCalls++;
            if (failing) {
                throw new RatesUnavailableException("feed down", null);
            }
            SortedMap<LocalDate, BigDecimal> result = new TreeMap<>();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                result.put(d, btc);
            }
            return result;
        }
    }

    private final FakeFeed feed = new FakeFeed();
    private final MutableClock clock = new MutableClock();
    private final List<Object> events = new ArrayList<>();

    private CryptoPriceService service(boolean withFallback) {
        return new CryptoPriceService(feed, withFallback ? new SimulatedCryptoProvider(clock) : null, events::add, clock);
    }

    @Test
    void refreshPublishesLivePricesWith24hChange() {
        CryptoPriceService service = service(true);

        CryptoPricesResponse prices = service.refresh();

        assertThat(prices.source()).isEqualTo("FAKE");
        assertThat(prices.prices()).hasSize(5);
        assertThat(prices.prices().getFirst().symbol()).isEqualTo("BTC");
        assertThat(prices.prices().getFirst().change24h()).isEqualByComparingTo("11.11"); // 70000 vs 63000
        assertThat(service.usdPrice("btc")).isEqualByComparingTo("70000");
        assertThat(events).singleElement().isInstanceOf(CryptoPricesUpdatedEvent.class);
    }

    @Test
    void readsUseTheSnapshotAndOnlyRefreshWhenItIsOld() {
        CryptoPriceService service = service(true);
        service.refresh();
        int calls = feed.tickerCalls;

        clock.advance(Duration.ofSeconds(10));
        service.latest();
        assertThat(feed.tickerCalls).isEqualTo(calls);

        clock.advance(CryptoPriceService.REFRESH_ON_READ_AFTER);
        service.latest();
        assertThat(feed.tickerCalls).isEqualTo(calls + 1);
    }

    @Test
    void keepsRecentLivePricesWhenTheFeedFails() {
        CryptoPriceService service = service(true);
        service.refresh();
        feed.failing = true;

        clock.advance(Duration.ofSeconds(60));
        CryptoPricesResponse prices = service.refresh();

        assertThat(prices.source()).isEqualTo("FAKE");
        assertThat(service.usdPrice("BTC")).isEqualByComparingTo("70000");
    }

    @Test
    void fallsBackToLabelledSimulatedPricesOnceLiveDataIsTooOld() {
        CryptoPriceService service = service(true);
        service.refresh();
        feed.failing = true;

        clock.advance(CryptoPriceService.MAX_LIVE_AGE.plusSeconds(1));
        assertThat(service.refresh().source()).isEqualTo("SIMULATED");

        // Live again as soon as the feed recovers
        feed.failing = false;
        assertThat(service.refresh().source()).isEqualTo("FAKE");
    }

    @Test
    void returns503WithoutFallbackOrData() {
        feed.failing = true;
        CryptoPriceService service = service(false);

        assertThatThrownBy(service::latest).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void historyIsCachedAndFallsBack() {
        CryptoPriceService service = service(true);

        assertThat(service.history("BTC", 30).points()).hasSize(31);
        service.history("BTC", 30);
        assertThat(feed.historyCalls).isEqualTo(1);

        // A different range is a different cache entry; with the feed down it is simulated
        feed.failing = true;
        assertThat(service.history("BTC", 90).source()).isEqualTo("SIMULATED");
        // The cached live entry is still served after it expires, rather than simulated data
        clock.advance(CryptoPriceService.HISTORY_TTL.plusMinutes(1));
        assertThat(service.history("BTC", 30).source()).isEqualTo("FAKE");
    }

    @Test
    void rejectsNonCryptoSymbols() {
        CryptoPriceService service = service(true);

        assertThatThrownBy(() -> service.usdPrice("EUR")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.history("DOGE", 30)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.history("BTC", 1000)).isInstanceOf(BadRequestException.class);
    }
}
