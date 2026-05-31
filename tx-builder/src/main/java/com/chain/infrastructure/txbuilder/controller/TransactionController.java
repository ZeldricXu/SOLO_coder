package com.chain.infrastructure.txbuilder.controller;

import com.chain.infrastructure.common.dto.ApiResponse;
import com.chain.infrastructure.persistence.entity.ChainTransaction;
import com.chain.infrastructure.txbuilder.dto.TransactionRequest;
import com.chain.infrastructure.txbuilder.dto.TransactionResult;
import com.chain.infrastructure.txbuilder.service.TransactionBuilderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionBuilderService transactionBuilderService;

    @PostMapping
    public Mono<ApiResponse<TransactionResult>> buildTransaction(@RequestBody TransactionRequest request) {
        return transactionBuilderService.buildTransaction(request)
                .map(ApiResponse::created);
    }

    @PostMapping("/{txId}/sign")
    public Mono<ApiResponse<TransactionResult>> signTransaction(
            @PathVariable String txId,
            @RequestParam String privateKey) {
        return transactionBuilderService.signTransaction(txId, privateKey)
                .map(ApiResponse::success);
    }

    @GetMapping("/{txId}")
    public Mono<ApiResponse<ChainTransaction>> getTransaction(@PathVariable String txId) {
        return transactionBuilderService.getTransaction(txId)
                .map(ApiResponse::success);
    }
}
