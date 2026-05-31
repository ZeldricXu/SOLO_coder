package com.didauth.module.hdwallet.controller;

import com.didauth.common.response.ApiResponse;
import com.didauth.core.entity.AddressBook;
import com.didauth.core.entity.HdWallet;
import com.didauth.module.hdwallet.dto.AddressBookRequest;
import com.didauth.module.hdwallet.dto.DeriveAddressRequest;
import com.didauth.module.hdwallet.dto.DeriveAddressResponse;
import com.didauth.module.hdwallet.service.HdWalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/hdwallet")
@RequiredArgsConstructor
public class HdWalletController {

    private final HdWalletService hdWalletService;

    @PostMapping("/derive")
    public Mono<ApiResponse<DeriveAddressResponse>> deriveAddress(@Valid @RequestBody DeriveAddressRequest request) {
        return hdWalletService.deriveAddress(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/wallets")
    public Mono<ApiResponse<List<HdWallet>>> listWallets(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String chainType) {
        return hdWalletService.listWallets(userId, chainType)
                .map(ApiResponse::success);
    }

    @GetMapping("/wallets/{walletId}")
    public Mono<ApiResponse<HdWallet>> getWallet(@PathVariable String walletId) {
        return hdWalletService.getWallet(walletId)
                .map(ApiResponse::success);
    }

    @PostMapping("/addressbook")
    public Mono<ApiResponse<String>> addAddressBook(@Valid @RequestBody AddressBookRequest request) {
        return hdWalletService.addAddressBook(request)
                .map(id -> ApiResponse.success(201, id));
    }

    @GetMapping("/addressbook")
    public Mono<ApiResponse<List<AddressBook>>> listAddressBook(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String chainType) {
        return hdWalletService.listAddressBook(userId, chainType)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/addressbook/{id}")
    public Mono<ApiResponse<Void>> deleteAddressBook(@PathVariable String id) {
        return hdWalletService.deleteAddressBook(id)
                .then(Mono.just(ApiResponse.success(null)));
    }
}
