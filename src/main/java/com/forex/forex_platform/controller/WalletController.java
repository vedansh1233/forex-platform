package com.forex.forex_platform.controller;

import com.forex.forex_platform.dto.ConversionResponse;
import com.forex.forex_platform.dto.ConvertRequest;
import com.forex.forex_platform.dto.PageResponse;
import com.forex.forex_platform.dto.TransactionResponse;
import com.forex.forex_platform.dto.WalletResponse;
import com.forex.forex_platform.service.WalletService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
@Tag(name = "Wallet")
public class WalletController {

    private final WalletService walletService;

    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    public WalletResponse createWallet(Authentication authentication) {
        return walletService.createWallet(authentication.getName());
    }

    @GetMapping("/my")
    public WalletResponse getMyWallet(Authentication authentication) {
        return walletService.getMyWallet(authentication.getName());
    }

    /** Exchange part of the wallet from one currency to another at the latest rate. */
    @PostMapping("/convert")
    public ConversionResponse convert(Authentication authentication,
                                      @Valid @RequestBody ConvertRequest request) {
        return walletService.convert(authentication.getName(), request);
    }

    /** Ledger of deposits and conversions, newest first. */
    @GetMapping("/transactions")
    public PageResponse<TransactionResponse> transactions(Authentication authentication,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return walletService.getTransactions(authentication.getName(), page, size);
    }
}
