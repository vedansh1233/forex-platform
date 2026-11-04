package com.forex.forex_platform.scheduling;

import com.forex.forex_platform.service.AlertService;
import com.forex.forex_platform.service.CryptoPriceService;
import com.forex.forex_platform.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background jobs (enabled by {@link com.forex.forex_platform.config.SchedulingConfig}).
 * Each job catches its own errors so one bad run never stops the schedule.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketJobs {

    private final CryptoPriceService cryptoPriceService;
    private final OrderService orderService;
    private final AlertService alertService;

    /** Refreshes crypto prices; each refresh is pushed to WebSocket clients. */
    @Scheduled(fixedDelayString = "${app.crypto.refresh-ms:5000}")
    public void refreshCryptoPrices() {
        try {
            cryptoPriceService.refresh();
        } catch (RuntimeException e) {
            log.warn("Crypto price refresh failed: {}", e.getMessage());
        }
    }

    /** Fills limit orders and triggers alerts whose targets have been reached. */
    @Scheduled(fixedDelayString = "${app.orders.match-ms:10000}", initialDelay = 5000)
    public void matchOrdersAndAlerts() {
        try {
            int filled = orderService.matchOpenOrders();
            int triggered = alertService.checkActiveAlerts();
            if (filled > 0 || triggered > 0) {
                log.info("Matching run: {} order(s) filled, {} alert(s) triggered", filled, triggered);
            }
        } catch (RuntimeException e) {
            log.error("Order/alert matching failed", e);
        }
    }
}
