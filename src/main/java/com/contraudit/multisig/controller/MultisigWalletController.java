package com.contraudit.multisig.controller;

import com.contraudit.common.ApiResponse;
import com.contraudit.multisig.dto.CreateMultisigWalletRequest;
import com.contraudit.multisig.entity.MultisigSigner;
import com.contraudit.multisig.entity.MultisigWallet;
import com.contraudit.multisig.service.MultisigWalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/multisig/wallets")
@RequiredArgsConstructor
public class MultisigWalletController {

    private final MultisigWalletService walletService;

    @PostMapping
    public Mono<ApiResponse<MultisigWallet>> createWallet(@Valid @RequestBody CreateMultisigWalletRequest request) {
        return Mono.just(ApiResponse.created(walletService.createWallet(request)));
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<MultisigWallet>> getWallet(@PathVariable String id) {
        return Mono.just(ApiResponse.success(walletService.getWallet(id)));
    }

    @GetMapping
    public Mono<ApiResponse<List<MultisigWallet>>> listWallets(
            @RequestParam(required = false) String chainType,
            @RequestParam(required = false) Integer status) {
        return Mono.just(ApiResponse.success(walletService.listWallets(chainType, status)));
    }

    @GetMapping("/{id}/signers")
    public Mono<ApiResponse<List<MultisigSigner>>> getWalletSigners(@PathVariable String id) {
        return Mono.just(ApiResponse.success(walletService.getWalletSigners(id)));
    }
}
