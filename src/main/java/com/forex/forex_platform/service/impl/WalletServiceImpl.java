package com.forex.forex_platform.service.impl;

import com.forex.forex_platform.dto.ConversionResponse;
import com.forex.forex_platform.dto.ConvertRequest;
import com.forex.forex_platform.dto.PageResponse;
import com.forex.forex_platform.dto.TransactionResponse;
import com.forex.forex_platform.dto.WalletResponse;
import com.forex.forex_platform.dto.WalletResponse.HoldingResponse;
import com.forex.forex_platform.entity.Holding;
import com.forex.forex_platform.entity.TransactionType;
import com.forex.forex_platform.entity.User;
import com.forex.forex_platform.entity.Wallet;
import com.forex.forex_platform.entity.WalletTransaction;
import com.forex.forex_platform.exception.BadRequestException;
import com.forex.forex_platform.exception.ConflictException;
import com.forex.forex_platform.exception.ResourceNotFoundException;
import com.forex.forex_platform.exception.ServiceUnavailableException;
import com.forex.forex_platform.market.Asset;
import com.forex.forex_platform.market.Assets;
import com.forex.forex_platform.repository.HoldingRepository;
import com.forex.forex_platform.repository.WalletRepository;
import com.forex.forex_platform.repository.WalletTransactionRepository;
import com.forex.forex_platform.service.PricingService;
import com.forex.forex_platform.service.RatesService;
import com.forex.forex_platform.service.UserService;
import com.forex.forex_platform.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Wallet model: the base currency (USD) cash lives in {@code Wallet.balance}; every other asset
 * (currency or crypto) the user acquires lives in a {@link Holding} row. Balance changes go
 * through {@link WalletLedger}.
 */
@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    /** Demo (paper-trading) balance credited to every new wallet. */
    static final BigDecimal STARTING_BALANCE = new BigDecimal("10000.00");
    static final int MAX_PAGE_SIZE = 100;

    private final WalletRepository walletRepository;
    private final HoldingRepository holdingRepository;
    private final WalletTransactionRepository transactionRepository;
    private final UserService userService;
    private final RatesService ratesService;
    private final PricingService pricingService;
    private final WalletLedger ledger;

    @Override
    @Transactional
    public WalletResponse createWallet(String email) {

        User user = userService.getByEmail(email);

        if (walletRepository.existsByUser(user)) {
            throw new ConflictException("Wallet already exists");
        }

        Wallet wallet = new Wallet();
        wallet.setUser(user);
        wallet.setBalance(STARTING_BALANCE);
        wallet.setCurrency(Assets.BASE);
        wallet = walletRepository.save(wallet);

        WalletTransaction deposit = new WalletTransaction();
        deposit.setWallet(wallet);
        deposit.setType(TransactionType.DEPOSIT);
        deposit.setToCurrency(Assets.BASE);
        deposit.setToAmount(STARTING_BALANCE);
        transactionRepository.save(deposit);

        return toResponse(wallet, List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public WalletResponse getMyWallet(String email) {
        Wallet wallet = findWallet(email);
        return toResponse(wallet, holdingRepository.findByWallet(wallet));
    }

    @Override
    @Transactional
    public ConversionResponse convert(String email, ConvertRequest request) {
        Asset from = Assets.require(request.from());
        Asset to = Assets.require(request.to());
        WalletLedger.validateTrade(from, to, request.amount());

        // Fetch the rate before taking the row lock, so a slow price feed doesn't hold it
        BigDecimal rate = pricingService.rate(from.code(), to.code());

        User user = userService.getByEmail(email);
        Wallet wallet = walletRepository.findByUserForUpdate(user)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));

        Map<String, Holding> holdings = ledger.holdings(wallet);
        WalletTransaction tx = ledger.convert(wallet, holdings, from, to, request.amount(), rate);

        return new ConversionResponse(
                TransactionResponse.from(tx),
                toResponse(wallet, new ArrayList<>(holdings.values())));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> getTransactions(String email, int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Wallet wallet = findWallet(email);
        return PageResponse.from(
                transactionRepository.findByWalletOrderByCreatedAtDescIdDesc(wallet, PageRequest.of(page, size)),
                TransactionResponse::from);
    }

    // --- helpers ---

    private Wallet findWallet(String email) {
        User user = userService.getByEmail(email);
        return walletRepository.findByUser(user)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));
    }

    private WalletResponse toResponse(Wallet wallet, List<Holding> holdings) {
        String base = wallet.getCurrency();

        List<HoldingResponse> items = new ArrayList<>();
        items.add(new HoldingResponse(base, scale(wallet.getBalance(), 2), scale(wallet.getBalance(), 2)));

        BigDecimal total = wallet.getBalance();
        boolean hasForeignFiat = false;
        List<Holding> nonZero = holdings.stream()
                .filter(h -> h.getAmount().signum() != 0)
                .sorted(Comparator.comparingInt(h -> Assets.order(h.getCurrency())))
                .toList();

        for (Holding holding : nonZero) {
            Asset asset = Assets.require(holding.getCurrency());
            hasForeignFiat |= !asset.isCrypto();
            BigDecimal value = null;
            try {
                value = pricingService.usdValue(asset.code(), holding.getAmount());
            } catch (ServiceUnavailableException e) {
                // Still return the wallet; this value is just unknown for now
            }
            total = value == null || total == null ? null : total.add(value);
            items.add(new HoldingResponse(asset.code(), scale(holding.getAmount(), asset.decimals()), value));
        }

        LocalDate valuationDate = null;
        if (hasForeignFiat && total != null) {
            valuationDate = ratesService.latest(base).date();
        }

        return new WalletResponse(
                wallet.getId(),
                wallet.getUser().getFullName(),
                scale(wallet.getBalance(), 2),
                base,
                wallet.getCreatedAt(),
                items,
                total == null ? null : scale(total, 2),
                valuationDate
        );
    }

    private static BigDecimal scale(BigDecimal amount, int decimals) {
        return amount.setScale(decimals, RoundingMode.HALF_EVEN);
    }
}
