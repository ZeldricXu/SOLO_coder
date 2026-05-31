package com.chainetl.modules.tx.controller;

import com.chainetl.common.dto.ApiResponse;
import com.chainetl.modules.tx.dto.ConstructTransactionRequest;
import com.chainetl.modules.tx.dto.SignTransactionRequest;
import com.chainetl.modules.tx.dto.SubmitTransactionRequest;
import com.chainetl.modules.tx.dto.TransactionResponse;
import com.chainetl.modules.tx.service.TransactionConstructionService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionConstructionService txService;

    @PostMapping("/construct")
    @Timed(value = "tx.construct.request", description = "Time taken to handle construct transaction request")
    public Mono<ResponseEntity<ApiResponse<TransactionResponse>>> constructTransaction(
            @Valid @RequestBody ConstructTransactionRequest request) {
        return txService.constructTransaction(request)
                .map(tx -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success(201, tx)));
    }

    @PostMapping("/sign")
    @Timed(value = "tx.sign.request", description = "Time taken to handle sign transaction request")
    public Mono<ResponseEntity<ApiResponse<TransactionResponse>>> signTransaction(
            @Valid @RequestBody SignTransactionRequest request) {
        return txService.signTransaction(request)
                .map(tx -> ResponseEntity.ok(ApiResponse.success(tx)));
    }

    @PostMapping("/submit")
    @Timed(value = "tx.submit.request", description = "Time taken to handle submit transaction request")
    public Mono<ResponseEntity<ApiResponse<TransactionResponse>>> submitTransaction(
            @Valid @RequestBody SubmitTransactionRequest request) {
        return txService.submitTransaction(request)
                .map(tx -> ResponseEntity.ok(ApiResponse.success(tx)));
    }

    @GetMapping("/{txId}")
    @Timed(value = "tx.get", description = "Time taken to get transaction")
    public Mono<ResponseEntity<ApiResponse<TransactionResponse>>> getTransaction(
            @PathVariable String txId) {
        return txService.getTransaction(txId)
                .map(tx -> ResponseEntity.ok(ApiResponse.success(tx)));
    }

    @GetMapping
    @Timed(value = "tx.list", description = "Time taken to list transactions")
    public Mono<ResponseEntity<ApiResponse<List<TransactionResponse>>>> listTransactions(
            @RequestParam(required = false) String chainId,
            @RequestParam(required = false) String fromAddress,
            @RequestParam(required = false) String toAddress,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "100") Integer limit) {
        return txService.listTransactions(chainId, fromAddress, toAddress, status, limit)
                .map(txs -> ResponseEntity.ok(ApiResponse.success(txs)));
    }

    @PatchMapping("/{txId}/status")
    @Timed(value = "tx.status.update", description = "Time taken to update transaction status")
    public Mono<ResponseEntity<ApiResponse<TransactionResponse>>> updateTransactionStatus(
            @PathVariable String txId,
            @RequestParam String status,
            @RequestParam(required = false) String txHash) {
        return txService.updateTransactionStatus(txId, status, txHash)
                .map(tx -> ResponseEntity.ok(ApiResponse.success(tx)));
    }

    @GetMapping("/nonce/{chainId}/{address}")
    @Timed(value = "tx.nonce.get", description = "Time taken to get nonce")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> getNonce(
            @PathVariable String chainId,
            @PathVariable String address) {
        return txService.getNonce(chainId, address)
                .map(nonce -> ResponseEntity.ok(ApiResponse.success(nonce)));
    }
}
