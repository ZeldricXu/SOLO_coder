package com.didauth.module.tx.controller;

import com.didauth.common.response.ApiResponse;
import com.didauth.core.entity.ChainTransaction;
import com.didauth.module.tx.dto.BuildTransactionRequest;
import com.didauth.module.tx.dto.BuildTransactionResponse;
import com.didauth.module.tx.dto.SignTransactionRequest;
import com.didauth.module.tx.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/build")
    public Mono<ApiResponse<BuildTransactionResponse>> buildTransaction(@Valid @RequestBody BuildTransactionRequest request) {
        return transactionService.buildTransaction(request)
                .map(response -> ApiResponse.success(201, response));
    }

    @PostMapping("/sign")
    public Mono<ApiResponse<String>> signTransaction(@Valid @RequestBody SignTransactionRequest request) {
        return transactionService.signTransaction(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/{txId}/submit")
    public Mono<ApiResponse<String>> submitTransaction(@PathVariable String txId) {
        return transactionService.submitTransaction(txId)
                .map(ApiResponse::success);
    }

    @GetMapping("/{txId}")
    public Mono<ApiResponse<ChainTransaction>> getTransaction(@PathVariable String txId) {
        return transactionService.getTransaction(txId)
                .map(ApiResponse::success);
    }

    @GetMapping
    public Mono<ApiResponse<List<ChainTransaction>>> listTransactions(
            @RequestParam(required = false) String chainType,
            @RequestParam(required = false) String fromAddress,
            @RequestParam(required = false) String status) {
        return transactionService.listTransactions(chainType, fromAddress, status)
                .map(ApiResponse::success);
    }
}
