package com.forex.forex_platform.service.impl;

import com.forex.forex_platform.entity.Holding;
import com.forex.forex_platform.entity.TransactionType;
import com.forex.forex_platform.entity.Wallet;
import com.forex.forex_platform.entity.WalletTransaction;
import com.forex.forex_platform.exception.BadRequestException;
import com.forex.forex_platform.market.Asset;
import com.forex.forex_platform.market.Assets;
import com.forex.forex_platform.repository.HoldingRepository;
import com.forex.forex_platform.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one place balances change. Used by market conversions ({@link WalletServiceImpl}) and by
 * limit-order fills ({@link OrderServiceImpl}), so both follow the same rules. Callers must hold
 * the wallet's row lock ({@code WalletRepository.findBy...ForUpdate}) and run in a transaction.
 */
@Component
@RequiredArgsConstructor
class WalletLedger {

    private final HoldingRepository holdingRepository;
    private final WalletTransactionRepository transactionRepository;

    /** Holdings of a wallet keyed by asset code. */
    Map<String, Holding> holdings(Wallet wallet) {
        return holdingRepository.findByWallet(wallet).stream()
                .collect(Collectors.toMap(Holding::getCurrency, Function.identity()));
    }

    BigDecimal available(Wallet wallet, Map<String, Holding> holdings, String code) {
        if (code.equals(wallet.getCurrency())) {
            return wallet.getBalance();
        }
        Holding holding = holdings.get(code);
        return holding == null ? BigDecimal.ZERO : holding.getAmount();
    }

    /** Amount received for {@code amount} at {@code rate}, rounded down to the target asset's decimals. */
    static BigDecimal receivedAmount(BigDecimal amount, BigDecimal rate, Asset to) {
        // Round down so conversions never create money out of rounding
        return amount.multiply(rate).setScale(to.decimals(), RoundingMode.DOWN);
    }

    static String insufficientMessage(Asset asset, BigDecimal available) {
        return "Insufficient " + asset.code() + " balance: "
                + available.setScale(asset.decimals(), RoundingMode.DOWN).toPlainString() + " available";
    }

    /**
     * Checks funds and moves {@code amount} of {@code from} into {@code to} at {@code rate}, recording
     * the ledger entry.
     *
     * @throws BadRequestException if the balance is too low or the amount converts to nothing
     */
    WalletTransaction convert(Wallet lockedWallet, Map<String, Holding> holdings,
                              Asset from, Asset to, BigDecimal amount, BigDecimal rate) {
        BigDecimal available = available(lockedWallet, holdings, from.code());
        if (available.compareTo(amount) < 0) {
            throw new BadRequestException(insufficientMessage(from, available));
        }
        BigDecimal received = receivedAmount(amount, rate, to);
        if (received.signum() <= 0) {
            throw new BadRequestException("Amount is too small to convert");
        }

        adjust(lockedWallet, holdings, from.code(), amount.negate());
        adjust(lockedWallet, holdings, to.code(), received);

        WalletTransaction tx = new WalletTransaction();
        tx.setWallet(lockedWallet);
        tx.setType(TransactionType.CONVERSION);
        tx.setFromCurrency(from.code());
        tx.setFromAmount(amount);
        tx.setToCurrency(to.code());
        tx.setToAmount(received);
        tx.setRate(rate);
        return transactionRepository.save(tx);
    }

    private void adjust(Wallet wallet, Map<String, Holding> holdings, String code, BigDecimal delta) {
        if (code.equals(wallet.getCurrency())) {
            wallet.setBalance(wallet.getBalance().add(delta));
            return;
        }
        Holding holding = holdings.computeIfAbsent(code, c -> new Holding(wallet, c));
        holding.setAmount(holding.getAmount().add(delta));
        holdingRepository.save(holding);
    }

    /** Validates a trade request's assets and amount (400 on problems). */
    static void validateTrade(Asset from, Asset to, BigDecimal amount) {
        if (from.equals(to)) {
            throw new BadRequestException("Choose two different assets");
        }
        Assets.requireValidAmount(from, amount);
    }
}
