package com.forex.forex_platform.service.impl;

import com.forex.forex_platform.dto.OrderResponse;
import com.forex.forex_platform.dto.PlaceOrderRequest;
import com.forex.forex_platform.entity.LimitOrder;
import com.forex.forex_platform.entity.OrderStatus;
import com.forex.forex_platform.entity.User;
import com.forex.forex_platform.entity.Wallet;
import com.forex.forex_platform.exception.BadRequestException;
import com.forex.forex_platform.exception.ConflictException;
import com.forex.forex_platform.exception.ResourceNotFoundException;
import com.forex.forex_platform.exception.ServiceUnavailableException;
import com.forex.forex_platform.market.Asset;
import com.forex.forex_platform.market.Assets;
import com.forex.forex_platform.repository.LimitOrderRepository;
import com.forex.forex_platform.repository.WalletRepository;
import com.forex.forex_platform.service.OrderService;
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
 * Limit orders. Funds are checked when the order is placed and again when it fills; they are not
 * reserved in between, so an order whose funds were spent meanwhile ends as FAILED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    static final int MAX_OPEN_ORDERS = 20;

    private final LimitOrderRepository orderRepository;
    private final WalletRepository walletRepository;
    private final UserService userService;
    private final PricingService pricingService;
    private final WalletLedger ledger;
    private final OrderFiller filler;

    @Override
    @Transactional
    public OrderResponse place(String email, PlaceOrderRequest request) {
        Asset from = Assets.require(request.from());
        Asset to = Assets.require(request.to());
        WalletLedger.validateTrade(from, to, request.amount());
        // Price lookup (possibly a network call) before taking the wallet lock
        BigDecimal currentRate = currentRate(from.code(), to.code()).orElse(null);

        Wallet wallet = walletRepository.findByUserForUpdate(userService.getByEmail(email))
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));

        if (orderRepository.countByWalletAndStatus(wallet, OrderStatus.OPEN) >= MAX_OPEN_ORDERS) {
            throw new ConflictException("You can have at most " + MAX_OPEN_ORDERS + " open orders");
        }
        BigDecimal available = ledger.available(wallet, ledger.holdings(wallet), from.code());
        if (available.compareTo(request.amount()) < 0) {
            throw new BadRequestException(WalletLedger.insufficientMessage(from, available));
        }
        if (WalletLedger.receivedAmount(request.amount(), request.targetRate(), to).signum() <= 0) {
            throw new BadRequestException("Amount is too small to convert");
        }

        LimitOrder order = new LimitOrder();
        order.setWallet(wallet);
        order.setFromCurrency(from.code());
        order.setToCurrency(to.code());
        order.setAmount(request.amount());
        order.setTargetRate(request.targetRate());
        order = orderRepository.save(order);
        return OrderResponse.from(order, currentRate);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> list(String email) {
        Wallet wallet = findWallet(email);
        Map<String, Optional<BigDecimal>> rates = new HashMap<>();
        return orderRepository.findTop50ByWalletOrderByCreatedAtDescIdDesc(wallet).stream()
                .map(o -> OrderResponse.from(o, o.getStatus() != OrderStatus.OPEN ? null
                        : rates.computeIfAbsent(o.getFromCurrency() + "/" + o.getToCurrency(),
                        k -> currentRate(o.getFromCurrency(), o.getToCurrency())).orElse(null)))
                .toList();
    }

    @Override
    @Transactional
    public OrderResponse cancel(String email, Long orderId) {
        // Same lock as fills, so a cancel and a fill of the same order can't interleave
        Wallet wallet = walletRepository.findByUserForUpdate(userService.getByEmail(email))
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));
        LimitOrder order = orderRepository.findById(orderId)
                .filter(o -> o.getWallet().getId().equals(wallet.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (order.getStatus() != OrderStatus.OPEN) {
            throw new ConflictException("Only open orders can be cancelled; this one is " + order.getStatus());
        }
        order.setStatus(OrderStatus.CANCELLED);
        order.setClosedAt(LocalDateTime.now());
        return OrderResponse.from(order, null);
    }

    @Override
    public int matchOpenOrders() {
        List<LimitOrder> open = orderRepository.findByStatusOrderByIdAsc(OrderStatus.OPEN);
        // One price lookup per pair per run
        Map<String, Optional<BigDecimal>> rates = new HashMap<>();
        int filled = 0;
        for (LimitOrder order : open) {
            Optional<BigDecimal> rate = rates.computeIfAbsent(order.getFromCurrency() + "/" + order.getToCurrency(),
                    k -> currentRate(order.getFromCurrency(), order.getToCurrency()));
            if (rate.isEmpty() || rate.get().compareTo(order.getTargetRate()) < 0) {
                continue;
            }
            try {
                if (filler.tryFill(order.getId(), rate.get())) {
                    filled++;
                }
            } catch (RuntimeException e) {
                log.error("Could not fill order {}", order.getId(), e);
            }
        }
        return filled;
    }

    private Optional<BigDecimal> currentRate(String from, String to) {
        try {
            return Optional.of(pricingService.rate(from, to));
        } catch (ServiceUnavailableException e) {
            return Optional.empty();
        }
    }

    private Wallet findWallet(String email) {
        User user = userService.getByEmail(email);
        return walletRepository.findByUser(user)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));
    }
}
