package com.forex.forex_platform.service.impl;

import com.forex.forex_platform.entity.Holding;
import com.forex.forex_platform.entity.LimitOrder;
import com.forex.forex_platform.entity.OrderStatus;
import com.forex.forex_platform.entity.Wallet;
import com.forex.forex_platform.entity.WalletTransaction;
import com.forex.forex_platform.exception.BadRequestException;
import com.forex.forex_platform.market.Asset;
import com.forex.forex_platform.market.Assets;
import com.forex.forex_platform.repository.LimitOrderRepository;
import com.forex.forex_platform.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Fills one limit order in its own transaction. A separate bean from {@link OrderServiceImpl} so
 * that each order gets its own transaction (and its own wallet lock) when the matcher loops.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderFiller {

    private final LimitOrderRepository orderRepository;
    private final WalletRepository walletRepository;
    private final WalletLedger ledger;

    /**
     * Fills the order at {@code rate} if it is still open and the rate meets its target.
     *
     * @return true if the order filled
     */
    @Transactional
    public boolean tryFill(Long orderId, BigDecimal rate) {
        // Lock the wallet first, then read the order: a concurrent cancel takes the same lock,
        // so the order's status can't change between this check and the fill
        Long walletId = orderRepository.findWalletIdById(orderId).orElse(null);
        if (walletId == null) {
            return false;
        }
        Wallet wallet = walletRepository.findByIdForUpdate(walletId).orElse(null);
        LimitOrder order = orderRepository.findById(orderId).orElse(null);
        if (wallet == null || order == null || order.getStatus() != OrderStatus.OPEN
                || rate.compareTo(order.getTargetRate()) < 0) {
            return false;
        }

        Asset from = Assets.require(order.getFromCurrency());
        Asset to = Assets.require(order.getToCurrency());
        Map<String, Holding> holdings = ledger.holdings(wallet);
        LocalDateTime now = LocalDateTime.now();
        try {
            WalletTransaction tx = ledger.convert(wallet, holdings, from, to, order.getAmount(), rate);
            order.setStatus(OrderStatus.FILLED);
            order.setFillRate(rate);
            order.setReceivedAmount(tx.getToAmount());
            order.setTransactionId(tx.getId());
            order.setClosedAt(now);
            log.info("Filled order {}: {} {} -> {} {} at {}", orderId, order.getAmount(), from.code(),
                    tx.getToAmount(), to.code(), rate);
            return true;
        } catch (BadRequestException e) {
            // The ledger checks before changing anything, so nothing needs rolling back
            order.setStatus(OrderStatus.FAILED);
            order.setFailureReason(e.getMessage());
            order.setClosedAt(now);
            log.info("Order {} failed at its target: {}", orderId, e.getMessage());
            return false;
        }
    }
}
