package com.didauth.module.hdwallet.enhanced;

import com.didauth.common.response.ApiResponse;
import com.didauth.core.entity.HdWallet;
import com.didauth.module.hdwallet.dto.DeriveAddressResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/hdwallet")
@RequiredArgsConstructor
public class EnhancedHdWalletController {

    private final EnhancedHdWalletService enhancedHdWalletService;

    @PostMapping("/batch/derive")
    public Mono<ApiResponse<List<DeriveAddressResponse>>> batchDeriveAddresses(
            @Valid @RequestBody BatchDeriveRequest request) {
        return enhancedHdWalletService.batchDeriveAddresses(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/batch/addressbook")
    public Mono<ApiResponse<Map<String, Object>>> batchAddAddressBook(
            @Valid @RequestBody BatchAddressBookRequest request) {
        return enhancedHdWalletService.batchAddAddressBook(request)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/batch/addressbook")
    public Mono<ApiResponse<Map<String, Object>>> batchDeleteAddressBook(
            @RequestBody Map<String, List<String>> request) {
        List<String> ids = request.get("ids");
        return enhancedHdWalletService.batchDeleteAddressBook(ids)
                .map(ApiResponse::success);
    }

    @PostMapping("/batch/wallets")
    public Mono<ApiResponse<List<HdWallet>>> batchGetWallets(
            @RequestBody Map<String, List<String>> request) {
        List<String> walletIds = request.get("walletIds");
        return enhancedHdWalletService.batchGetWallets(walletIds)
                .map(ApiResponse::success);
    }

    @GetMapping("/batch/metrics")
    public Mono<ApiResponse<Map<String, Object>>> getBatchMetrics() {
        return enhancedHdWalletService.getBatchMetrics()
                .map(ApiResponse::success);
    }
}
