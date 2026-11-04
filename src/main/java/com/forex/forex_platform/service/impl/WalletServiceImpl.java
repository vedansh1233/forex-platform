package com.forex.forex_platform.service.impl;

import com.forex.forex_platform.dto.WalletResponse;
import com.forex.forex_platform.entity.User;
import com.forex.forex_platform.entity.Wallet;
import com.forex.forex_platform.exception.ConflictException;
import com.forex.forex_platform.exception.ResourceNotFoundException;
import com.forex.forex_platform.repository.WalletRepository;
import com.forex.forex_platform.service.UserService;
import com.forex.forex_platform.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    /** Demo (paper-trading) balance credited to every new wallet. */
    static final BigDecimal STARTING_BALANCE = new BigDecimal("10000.00");
    static final String DEFAULT_CURRENCY = "USD";

    private final WalletRepository walletRepository;
    private final UserService userService;

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
        wallet.setCurrency(DEFAULT_CURRENCY);

        return WalletResponse.from(walletRepository.save(wallet));
    }

    @Override
    @Transactional(readOnly = true)
    public WalletResponse getMyWallet(String email) {

        User user = userService.getByEmail(email);

        Wallet wallet = walletRepository.findByUser(user)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));

        return WalletResponse.from(wallet);
    }
}
