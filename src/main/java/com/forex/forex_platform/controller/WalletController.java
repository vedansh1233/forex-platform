package com.forex.forex_platform.controller;

import com.forex.forex_platform.dto.WalletResponse;
import com.forex.forex_platform.service.WalletService;
import io.swagger.v3.oas.annotations.tags.Tag;
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
}
