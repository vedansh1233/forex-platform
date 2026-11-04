package com.forex.forex_platform.service;

import com.forex.forex_platform.dto.ConversionResponse;
import com.forex.forex_platform.dto.ConvertRequest;
import com.forex.forex_platform.dto.PageResponse;
import com.forex.forex_platform.dto.TransactionResponse;
import com.forex.forex_platform.dto.WalletResponse;

public interface WalletService {

    WalletResponse createWallet(String email);

    WalletResponse getMyWallet(String email);

    ConversionResponse convert(String email, ConvertRequest request);

    PageResponse<TransactionResponse> getTransactions(String email, int page, int size);
}
