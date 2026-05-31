package com.solocoder.platform.transaction.adapter.rest;

import com.solocoder.platform.persistence.common.ApiResponse;
import com.solocoder.platform.transaction.adapter.dto.TransactionBuildRequestDto;
import com.solocoder.platform.transaction.adapter.dto.TransactionResponseDto;
import com.solocoder.platform.transaction.adapter.dto.TransactionSignRequestDto;
import com.solocoder.platform.transaction.application.service.TransactionApplicationService;
import com.solocoder.platform.transaction.domain.model.BuiltTransaction;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionApplicationService transactionApplicationService;

    @PostMapping("/build")
    public ResponseEntity<ApiResponse<TransactionResponseDto>> build(
            @Valid @RequestBody TransactionBuildRequestDto request) {
        BuiltTransaction.MultisigStrategy multisigStrategy = null;
        if (request.getMultisigStrategy() != null &&
                request.getMultisigStrategy().getType() != null &&
                !"NONE".equals(request.getMultisigStrategy().getType())) {
            multisigStrategy = BuiltTransaction.MultisigStrategy.builder()
                    .type(BuiltTransaction.MultisigStrategy.MultisigStrategyType.valueOf(
                            request.getMultisigStrategy().getType().toUpperCase()))
                    .threshold(request.getMultisigStrategy().getThreshold())
                    .owners(request.getMultisigStrategy().getOwners())
                    .walletAddress(request.getMultisigStrategy().getWalletAddress())
                    .build();
        }

        BuiltTransaction transaction;
        if (Boolean.TRUE.equals(request.getOptimizeGas())) {
            transaction = transactionApplicationService.buildOptimizedTransaction(
                    request.getChainId(), request.getFrom(), request.getTo(),
                    request.getValue(), request.getData(), request.getNonce(), multisigStrategy);
        } else {
            transaction = transactionApplicationService.buildTransaction(
                    request.getChainId(), request.getFrom(), request.getTo(),
                    request.getValue(), request.getData(), request.getNonce(), multisigStrategy);
        }

        return ResponseEntity.ok(ApiResponse.success(toResponseDto(transaction)));
    }

    @PostMapping("/sign")
    public ResponseEntity<ApiResponse<TransactionResponseDto>> sign(
            @Valid @RequestBody TransactionSignRequestDto request) {
        BuiltTransaction transaction = transactionApplicationService.signTransaction(
                request.getTxId(), request.getSigner(), request.getPrivateKey());
        return ResponseEntity.ok(ApiResponse.success(toResponseDto(transaction)));
    }

    @GetMapping("/{txId}")
    public ResponseEntity<ApiResponse<TransactionResponseDto>> getTransaction(@PathVariable String txId) {
        BuiltTransaction transaction = transactionApplicationService.getTransaction(txId);
        return ResponseEntity.ok(ApiResponse.success(toResponseDto(transaction)));
    }

    @GetMapping("/{txId}/broadcast-data")
    public ResponseEntity<ApiResponse<String>> getBroadcastData(@PathVariable String txId) {
        String data = transactionApplicationService.getBroadcastData(txId);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/from/{from}")
    public ResponseEntity<ApiResponse<List<TransactionResponseDto>>> getTransactionsByFrom(
            @PathVariable String from,
            @RequestParam(defaultValue = "10") int limit) {
        List<BuiltTransaction> transactions = transactionApplicationService.getTransactionsByFrom(from, limit);
        List<TransactionResponseDto> dtos = transactions.stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<TransactionResponseDto>>> getTransactionsByStatus(
            @PathVariable String status,
            @RequestParam(defaultValue = "10") int limit) {
        List<BuiltTransaction> transactions = transactionApplicationService.getTransactionsByStatus(
                BuiltTransaction.TransactionStatus.valueOf(status.toUpperCase()), limit);
        List<TransactionResponseDto> dtos = transactions.stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @DeleteMapping("/{txId}")
    public ResponseEntity<ApiResponse<Boolean>> deleteTransaction(@PathVariable String txId) {
        boolean result = transactionApplicationService.deleteTransaction(txId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private TransactionResponseDto toResponseDto(BuiltTransaction transaction) {
        TransactionResponseDto.GasSettingsDto gasSettings = null;
        if (transaction.getGasSettings() != null) {
            gasSettings = TransactionResponseDto.GasSettingsDto.builder()
                    .gasLimit(transaction.getGasSettings().getGasLimit())
                    .gasPrice(transaction.getGasSettings().getGasPrice())
                    .maxPriorityFeePerGas(transaction.getGasSettings().getMaxPriorityFeePerGas())
                    .maxFeePerGas(transaction.getGasSettings().getMaxFeePerGas())
                    .gasType(transaction.getGasSettings().getGasType() != null ?
                            transaction.getGasSettings().getGasType().name() : null)
                    .build();
        }

        TransactionResponseDto.MultisigStrategyDto multisigStrategy = null;
        if (transaction.getMultisigStrategy() != null) {
            multisigStrategy = TransactionResponseDto.MultisigStrategyDto.builder()
                    .type(transaction.getMultisigStrategy().getType() != null ?
                            transaction.getMultisigStrategy().getType().name() : null)
                    .threshold(transaction.getMultisigStrategy().getThreshold())
                    .owners(transaction.getMultisigStrategy().getOwners())
                    .walletAddress(transaction.getMultisigStrategy().getWalletAddress())
                    .build();
        }

        List<TransactionResponseDto.SignatureDto> signatures = null;
        if (transaction.getSignatures() != null) {
            signatures = transaction.getSignatures().stream()
                    .map(sig -> TransactionResponseDto.SignatureDto.builder()
                            .signer(sig.getSigner())
                            .signatureData(sig.getSignatureData())
                            .signedAt(sig.getSignedAt())
                            .build())
                    .collect(Collectors.toList());
        }

        String broadcastData = null;
        if (transaction.isReadyToBroadcast()) {
            try {
                broadcastData = transactionApplicationService.getBroadcastData(transaction.getTxId());
            } catch (Exception ignored) {}
        }

        return TransactionResponseDto.builder()
                .txId(transaction.getTxId())
                .chainId(transaction.getChainId())
                .from(transaction.getFrom())
                .to(transaction.getTo())
                .value(transaction.getValue())
                .data(transaction.getData())
                .nonce(transaction.getNonce())
                .gasSettings(gasSettings)
                .multisigStrategy(multisigStrategy)
                .status(transaction.getStatus() != null ? transaction.getStatus().name() : null)
                .unsignedData(transaction.getUnsignedData())
                .signedData(transaction.getSignedData())
                .signatureCount(transaction.getSignatureCount())
                .readyToBroadcast(transaction.isReadyToBroadcast())
                .signatures(signatures)
                .broadcastData(broadcastData)
                .createdAt(transaction.getCreatedAt())
                .updatedAt(transaction.getUpdatedAt())
                .build();
    }
}
