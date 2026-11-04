package com.forex.forex_platform.service;

import com.forex.forex_platform.dto.WalletResponse;

public interface WalletService {

    WalletResponse createWallet(String email);

    WalletResponse getMyWallet(String email);
}