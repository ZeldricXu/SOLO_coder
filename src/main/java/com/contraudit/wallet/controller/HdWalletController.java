package com.contraudit.wallet.controller;

import com.contraudit.common.ApiResponse;
import com.contraudit.wallet.dto.CreateWalletRequest;
import com.contraudit.wallet.dto.DeriveAddressRequest;
import com.contraudit.wallet.entity.DerivedAddress;
import com.contraudit.wallet.entity.HdWallet;
import com.contraudit.wallet.service.HdWalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class HdWalletController {

    private final HdWalletService hdWalletService;

    @PostMapping
    public Mono<ApiResponse<HdWallet>> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        return Mono.just(ApiResponse.created(hdWalletService.createWallet(request)));
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<HdWallet>> getWallet(@PathVariable String id) {
        return Mono.just(ApiResponse.success(hdWalletService.getWallet(id)));
    }

    @GetMapping
    public Mono<ApiResponse<List<HdWallet>>> listWallets(
            @RequestParam(required = false) String chainType,
            @RequestParam(required = false) Integer status) {
        return Mono.just(ApiResponse.success(hdWalletService.listWallets(chainType, status)));
    }

    @PostMapping("/derive")
    public Mono<ApiResponse<DerivedAddress>> deriveAddress(@Valid @RequestBody DeriveAddressRequest request) {
        return Mono.just(ApiResponse.created(hdWalletService.deriveAddress(request)));
    }

    @GetMapping("/{walletId}/addresses")
    public Mono<ApiResponse<List<DerivedAddress>>> listDerivedAddresses(@PathVariable String walletId) {
        return Mono.just(ApiResponse.success(hdWalletService.listDerivedAddresses(walletId)));
    }

    @PostMapping("/{id}/activate")
    public Mono<ApiResponse<HdWallet>> activateWallet(@PathVariable String id) {
        return Mono.just(ApiResponse.success(hdWalletService.activateWallet(id)));
    }

    @PostMapping("/{id}/deactivate")
    public Mono<ApiResponse<HdWallet>> deactivateWallet(@PathVariable String id) {
        return Mono.just(ApiResponse.success(hdWalletService.deactivateWallet(id)));
    }

    @PostMapping("/{id}/archive")
    public Mono<ApiResponse<HdWallet>> archiveWallet(@PathVariable String id) {
        return Mono.just(ApiResponse.success(hdWalletService.archiveWallet(id)));
    }
}
