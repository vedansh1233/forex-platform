package com.forex.forex_platform.service.impl;

import com.forex.forex_platform.dto.AlertResponse;
import com.forex.forex_platform.dto.CreateAlertRequest;
import com.forex.forex_platform.entity.AlertDirection;
import com.forex.forex_platform.entity.AlertStatus;
import com.forex.forex_platform.entity.PriceAlert;
import com.forex.forex_platform.entity.User;
import com.forex.forex_platform.exception.BadRequestException;
import com.forex.forex_platform.exception.ConflictException;
import com.forex.forex_platform.exception.ResourceNotFoundException;
import com.forex.forex_platform.exception.ServiceUnavailableException;
import com.forex.forex_platform.market.Asset;
import com.forex.forex_platform.market.Assets;
import com.forex.forex_platform.repository.PriceAlertRepository;
import com.forex.forex_platform.service.AlertService;
import com.forex.forex_platform.service.PricingService;
import com.forex.forex_platform.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Price alerts. Each alert triggers once; the frontend polls the list and shows unseen
 * triggered alerts as notifications.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertServiceImpl implements AlertService {

    static final int MAX_ACTIVE_ALERTS = 20;

    private final PriceAlertRepository alertRepository;
    private final UserService userService;
    private final PricingService pricingService;

    @Override
    @Transactional
    public AlertResponse create(String email, CreateAlertRequest request) {
        Asset base = Assets.require(request.base());
        Asset quote = Assets.require(request.quote());
        if (base.equals(quote)) {
            throw new BadRequestException("Choose two different assets");
        }
        User user = userService.getByEmail(email);
        if (alertRepository.countByUserAndStatus(user, AlertStatus.ACTIVE) >= MAX_ACTIVE_ALERTS) {
            throw new ConflictException("You can have at most " + MAX_ACTIVE_ALERTS + " active alerts");
        }

        PriceAlert alert = new PriceAlert();
        alert.setUser(user);
        alert.setBaseCurrency(base.code());
        alert.setQuoteCurrency(quote.code());
        alert.setDirection(request.direction());
        alert.setTargetRate(request.targetRate());
        alert = alertRepository.save(alert);
        return AlertResponse.from(alert, currentRate(base.code(), quote.code()).orElse(null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertResponse> list(String email) {
        User user = userService.getByEmail(email);
        Map<String, Optional<BigDecimal>> rates = new HashMap<>();
        return alertRepository.findTop50ByUserOrderByCreatedAtDescIdDesc(user).stream()
                .map(a -> AlertResponse.from(a, a.getStatus() != AlertStatus.ACTIVE ? null
                        : rates.computeIfAbsent(a.getBaseCurrency() + "/" + a.getQuoteCurrency(),
                        k -> currentRate(a.getBaseCurrency(), a.getQuoteCurrency())).orElse(null)))
                .toList();
    }

    @Override
    @Transactional
    public AlertResponse cancel(String email, Long alertId) {
        User user = userService.getByEmail(email);
        PriceAlert alert = alertRepository.findById(alertId)
                .filter(a -> a.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Alert not found"));
        if (alertRepository.cancel(alertId, user) == 0) {
            throw new ConflictException("Only active alerts can be cancelled; this one is " + alert.getStatus());
        }
        alert.setStatus(AlertStatus.CANCELLED);
        return AlertResponse.from(alert, null);
    }

    @Override
    @Transactional
    public void markSeen(String email) {
        alertRepository.markSeen(userService.getByEmail(email));
    }

    /** Not one big transaction: price lookups may hit the network, and each trigger commits on its own. */
    @Override
    public int checkActiveAlerts() {
        Map<String, Optional<BigDecimal>> rates = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        int triggered = 0;
        for (PriceAlert alert : alertRepository.findByStatusOrderByIdAsc(AlertStatus.ACTIVE)) {
            Optional<BigDecimal> rate = rates.computeIfAbsent(alert.getBaseCurrency() + "/" + alert.getQuoteCurrency(),
                    k -> currentRate(alert.getBaseCurrency(), alert.getQuoteCurrency()));
            if (rate.isEmpty() || !isMet(alert, rate.get())) {
                continue;
            }
            // Conditional update: does nothing if the user cancelled it in the meantime
            if (alertRepository.trigger(alert.getId(), rate.get(), now) == 1) {
                triggered++;
                log.info("Alert {} triggered: {}/{} at {}", alert.getId(), alert.getBaseCurrency(),
                        alert.getQuoteCurrency(), rate.get());
            }
        }
        return triggered;
    }

    static boolean isMet(PriceAlert alert, BigDecimal rate) {
        int cmp = rate.compareTo(alert.getTargetRate());
        return alert.getDirection() == AlertDirection.ABOVE ? cmp >= 0 : cmp <= 0;
    }

    private Optional<BigDecimal> currentRate(String base, String quote) {
        try {
            return Optional.of(pricingService.rate(base, quote));
        } catch (ServiceUnavailableException e) {
            return Optional.empty();
        }
    }
}
