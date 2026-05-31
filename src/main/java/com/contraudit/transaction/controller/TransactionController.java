package com.contraudit.transaction.controller;

import com.contraudit.common.ApiResponse;
import com.contraudit.transaction.entity.PendingTransaction;
import com.contraudit.transaction.entity.SigningPolicy;
import com.contraudit.transaction.entity.TransactionTemplate;
import com.contraudit.transaction.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/templates")
    public Mono<ApiResponse<TransactionTemplate>> createTemplate(@Valid @RequestBody TransactionTemplate template) {
        return Mono.just(ApiResponse.created(transactionService.createTemplate(template)));
    }

    @GetMapping("/templates/{id}")
    public Mono<ApiResponse<TransactionTemplate>> getTemplate(@PathVariable String id) {
        return Mono.just(ApiResponse.success(transactionService.getTemplate(id)));
    }

    @GetMapping("/templates")
    public Mono<ApiResponse<List<TransactionTemplate>>> listTemplates(
            @RequestParam(required = false) String chainType,
            @RequestParam(required = false) String txType) {
        return Mono.just(ApiResponse.success(transactionService.listTemplates(chainType, txType)));
    }

    @PostMapping("/construct")
    public Mono<ApiResponse<PendingTransaction>> constructTransaction(
            @RequestParam String fromAddress,
            @RequestParam String toAddress,
            @RequestParam(required = false) BigDecimal value,
            @RequestParam(required = false) String data,
            @RequestParam(required = false) Long nonce,
            @RequestParam(required = false) Long gasLimit,
            @RequestParam(required = false) BigDecimal gasPrice,
            @RequestParam String chainType,
            @RequestParam(required = false) String policyId,
            @RequestParam(required = false) String templateId) {
        return Mono.just(ApiResponse.created(transactionService.constructTransaction(
                fromAddress, toAddress, value, data, nonce, gasLimit, gasPrice, chainType, policyId, templateId)));
    }

    @PostMapping("/construct/from-template")
    public Mono<ApiResponse<PendingTransaction>> constructFromTemplate(
            @RequestParam String templateId,
            @RequestParam String fromAddress,
            @RequestBody(required = false) Map<String, Object> params,
            @RequestParam(required = false) BigDecimal gasPrice,
            @RequestParam(required = false) Long nonce) {
        return Mono.just(ApiResponse.created(transactionService.constructFromTemplate(
                templateId, fromAddress, params, gasPrice, nonce)));
    }

    @PostMapping("/{txId}/sign")
    public Mono<ApiResponse<PendingTransaction>> signTransaction(
            @PathVariable String txId,
            @RequestParam String privateKey) {
        return Mono.just(ApiResponse.success(transactionService.signTransaction(txId, privateKey)));
    }

    @GetMapping("/{txId}")
    public Mono<ApiResponse<PendingTransaction>> getTransaction(@PathVariable String txId) {
        return Mono.just(ApiResponse.success(transactionService.getTransaction(txId)));
    }

    @GetMapping
    public Mono<ApiResponse<List<PendingTransaction>>> listTransactions(
            @RequestParam(required = false) String fromAddress,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String chainType) {
        return Mono.just(ApiResponse.success(transactionService.listTransactions(fromAddress, status, chainType)));
    }

    @PostMapping("/{txId}/status")
    public Mono<ApiResponse<PendingTransaction>> updateTransactionStatus(
            @PathVariable String txId,
            @RequestParam String status,
            @RequestParam(required = false) String txHash,
            @RequestParam(required = false) Long blockNumber) {
        return Mono.just(ApiResponse.success(transactionService.updateTransactionStatus(txId, status, txHash, blockNumber)));
    }

    @PostMapping("/policies")
    public Mono<ApiResponse<SigningPolicy>> createSigningPolicy(@Valid @RequestBody SigningPolicy policy) {
        return Mono.just(ApiResponse.created(transactionService.createSigningPolicy(policy)));
    }

    @GetMapping("/policies/{id}")
    public Mono<ApiResponse<SigningPolicy>> getSigningPolicy(@PathVariable String id) {
        return Mono.just(ApiResponse.success(transactionService.getSigningPolicy(id)));
    }

    @GetMapping("/policies")
    public Mono<ApiResponse<List<SigningPolicy>>> listSigningPolicies(
            @RequestParam(required = false) String chainType,
            @RequestParam(required = false) String policyType) {
        return Mono.just(ApiResponse.success(transactionService.listSigningPolicies(chainType, policyType)));
    }
}
