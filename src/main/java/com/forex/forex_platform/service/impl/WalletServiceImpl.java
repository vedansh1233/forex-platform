package com.forex.forex_platform.service.impl;

import com.forex.forex_platform.dto.ConversionResponse;
import com.forex.forex_platform.dto.ConvertRequest;
import com.forex.forex_platform.dto.PageResponse;
import com.forex.forex_platform.dto.RatesResponse;
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
import com.forex.forex_platform.repository.HoldingRepository;
import com.forex.forex_platform.repository.WalletRepository;
import com.forex.forex_platform.repository.WalletTransactionRepository;
import com.forex.forex_platform.service.RatesService;
import com.forex.forex_platform.service.UserService;
import com.forex.forex_platform.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Wallet model: the base currency (USD) cash lives in {@code Wallet.balance}; every other
 * currency the user acquires through conversions lives in a {@link Holding} row.
 */
@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    /** Demo (paper-trading) balance credited to every new wallet. */
    static final BigDecimal STARTING_BALANCE = new BigDecimal("10000.00");
    static final String BASE_CURRENCY = "USD";
    static final int MAX_PAGE_SIZE = 100;

    private final WalletRepository walletRepository;
    private final HoldingRepository holdingRepository;
    private final WalletTransactionRepository transactionRepository;
    private final UserService userService;
    private final RatesService ratesService;

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
        wallet.setCurrency(BASE_CURRENCY);
        wallet = walletRepository.save(wallet);

        WalletTransaction deposit = new WalletTransaction();
        deposit.setWallet(wallet);
        deposit.setType(TransactionType.DEPOSIT);
        deposit.setToCurrency(BASE_CURRENCY);
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

        String from = RatesService.requireSupported(request.from());
        String to = RatesService.requireSupported(request.to());
        if (from.equals(to)) {
            throw new BadRequestException("Choose two different currencies");
        }
        BigDecimal amount = request.amount();

        // Fetch the rate before taking the row lock, so a slow rates API doesn't hold it
        BigDecimal rate = ratesService.rate(from, to);
        // Round down so conversions never create money out of rounding
        BigDecimal received = amount.multiply(rate).setScale(2, RoundingMode.DOWN);

        User user = userService.getByEmail(email);
        Wallet wallet = walletRepository.findByUserForUpdate(user)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));

        Map<String, Holding> holdings = holdingRepository.findByWallet(wallet).stream()
                .collect(Collectors.toMap(Holding::getCurrency, Function.identity()));

        BigDecimal available = amountOf(wallet, holdings, from);
        if (available.compareTo(amount) < 0) {
            throw new BadRequestException(
                    "Insufficient " + from + " balance: " + available.setScale(2, RoundingMode.DOWN).toPlainString() + " available");
        }
        if (received.signum() <= 0) {
            throw new BadRequestException("Amount is too small to convert");
        }

        adjust(wallet, holdings, from, amount.negate());
        adjust(wallet, holdings, to, received);

        WalletTransaction tx = new WalletTransaction();
        tx.setWallet(wallet);
        tx.setType(TransactionType.CONVERSION);
        tx.setFromCurrency(from);
        tx.setFromAmount(amount);
        tx.setToCurrency(to);
        tx.setToAmount(received);
        tx.setRate(rate);
        tx = transactionRepository.save(tx);

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

    private BigDecimal amountOf(Wallet wallet, Map<String, Holding> holdings, String currency) {
        if (currency.equals(wallet.getCurrency())) {
            return wallet.getBalance();
        }
        Holding holding = holdings.get(currency);
        return holding == null ? BigDecimal.ZERO : holding.getAmount();
    }

    private void adjust(Wallet wallet, Map<String, Holding> holdings, String currency, BigDecimal delta) {
        if (currency.equals(wallet.getCurrency())) {
            wallet.setBalance(wallet.getBalance().add(delta));
            return;
        }
        Holding holding = holdings.computeIfAbsent(currency, c -> new Holding(wallet, c));
        holding.setAmount(holding.getAmount().add(delta));
        holdingRepository.save(holding);
    }

    private WalletResponse toResponse(Wallet wallet, List<Holding> holdings) {
        String base = wallet.getCurrency();

        RatesResponse rates = null;
        boolean hasForeign = holdings.stream().anyMatch(h -> h.getAmount().signum() != 0);
        if (hasForeign) {
            try {
                rates = ratesService.latest(base);
            } catch (ServiceUnavailableException e) {
                // Still return the wallet; values are just unknown for now
            }
        }

        List<HoldingResponse> items = new ArrayList<>();
        items.add(new HoldingResponse(base, scale(wallet.getBalance()), scale(wallet.getBalance())));

        BigDecimal total = wallet.getBalance();
        List<Holding> foreign = holdings.stream()
                .filter(h -> h.getAmount().signum() != 0)
                .sorted(Comparator.comparing(h -> RatesService.SUPPORTED_CURRENCIES.indexOf(h.getCurrency())))
                .toList();

        for (Holding holding : foreign) {
            BigDecimal value = null;
            BigDecimal rate = rates == null ? null : rates.rates().get(holding.getCurrency());
            if (rate != null && rate.signum() > 0) {
                value = holding.getAmount().divide(rate, MathContext.DECIMAL64).setScale(2, RoundingMode.HALF_EVEN);
            }
            if (value == null) {
                total = null;
            } else if (total != null) {
                total = total.add(value);
            }
            items.add(new HoldingResponse(holding.getCurrency(), scale(holding.getAmount()), value));
        }

        return new WalletResponse(
                wallet.getId(),
                wallet.getUser().getFullName(),
                scale(wallet.getBalance()),
                base,
                wallet.getCreatedAt(),
                items,
                total == null ? null : scale(total),
                hasForeign && rates != null ? rates.date() : null
        );
    }

    private static BigDecimal scale(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_EVEN);
    }
}
