package com.nftindexer.modules.transaction.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nftindexer.common.ApiResponse;
import com.nftindexer.common.PageResult;
import com.nftindexer.entity.TransactionRecord;
import com.nftindexer.modules.transaction.dto.TransactionConstructRequest;
import com.nftindexer.modules.transaction.dto.TransactionSignRequest;
import com.nftindexer.modules.transaction.dto.TransactionSubmitRequest;
import com.nftindexer.modules.transaction.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/construct")
    public Mono<ApiResponse<TransactionRecord>> constructTransaction(
            @Valid @RequestBody TransactionConstructRequest request) {
        return transactionService.constructTransaction(request)
                .map(tx -> ApiResponse.created(tx));
    }

    @PostMapping("/sign")
    public Mono<ApiResponse<TransactionRecord>> signTransaction(
            @Valid @RequestBody TransactionSignRequest request) {
        return transactionService.signTransaction(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/submit")
    public Mono<ApiResponse<TransactionRecord>> submitTransaction(
            @Valid @RequestBody TransactionSubmitRequest request) {
        return transactionService.submitTransaction(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/{txId}")
    public Mono<ApiResponse<TransactionRecord>> getTransaction(@PathVariable String txId) {
        return transactionService.getTransaction(txId)
                .map(ApiResponse::success);
    }

    @GetMapping
    public Mono<ApiResponse<PageResult<TransactionRecord>>> listTransactions(
            @RequestParam(required = false) String chainId,
            @RequestParam(required = false) String fromAddress,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return transactionService.listTransactions(chainId, fromAddress, status, pageNum, pageSize)
                .map(page -> ApiResponse.success(new PageResult<>(
                        page.getRecords(),
                        page.getTotal(),
                        page.getCurrent(),
                        page.getSize()
                )));
    }

    @PostMapping("/{txId}/confirmations")
    public Mono<ApiResponse<TransactionRecord>> updateConfirmation(
            @PathVariable String txId,
            @RequestBody Map<String, Object> request) {
        int confirmations = (int) request.getOrDefault("confirmations", 0);
        BigInteger gasUsed = request.get("gasUsed") != null ?
                new BigInteger(request.get("gasUsed").toString()) : null;
        BigInteger actualGasPrice = request.get("actualGasPrice") != null ?
                new BigInteger(request.get("actualGasPrice").toString()) : null;
        return transactionService.updateConfirmation(txId, confirmations, gasUsed, actualGasPrice)
                .map(ApiResponse::success);
    }

    @PostMapping("/{txId}/failed")
    public Mono<ApiResponse<TransactionRecord>> markFailed(
            @PathVariable String txId,
            @RequestBody Map<String, String> request) {
        String error = request.getOrDefault("error", "交易失败");
        return transactionService.markFailed(txId, error)
                .map(ApiResponse::success);
    }

    @GetMapping("/pending/{chainId}")
    public Mono<ApiResponse<List<TransactionRecord>>> getPendingTransactions(@PathVariable String chainId) {
        return transactionService.getPendingTransactions(chainId)
                .map(ApiResponse::success);
    }
}
