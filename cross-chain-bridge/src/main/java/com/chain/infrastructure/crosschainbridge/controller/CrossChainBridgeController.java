package com.chain.infrastructure.crosschainbridge.controller;

import com.chain.infrastructure.common.dto.ApiResponse;
import com.chain.infrastructure.crosschainbridge.dto.CrossChainTransferRequest;
import com.chain.infrastructure.crosschainbridge.dto.CrossChainTransferResult;
import com.chain.infrastructure.crosschainbridge.dto.MessageVerificationRequest;
import com.chain.infrastructure.crosschainbridge.service.CrossChainBridgeService;
import com.chain.infrastructure.persistence.entity.CrossChainTransfer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/bridge")
@RequiredArgsConstructor
public class CrossChainBridgeController {

    private final CrossChainBridgeService crossChainBridgeService;

    @PostMapping("/transfers")
    public Mono<ApiResponse<CrossChainTransferResult>> initiateTransfer(
            @RequestBody CrossChainTransferRequest request) {
        return crossChainBridgeService.initiateTransfer(request)
                .map(ApiResponse::created);
    }

    @PostMapping("/transfers/{transferId}/lock")
    public Mono<ApiResponse<CrossChainTransfer>> lockAssets(
            @PathVariable String transferId,
            @RequestParam String sourceTxHash) {
        return crossChainBridgeService.lockAssets(transferId, sourceTxHash)
                .map(ApiResponse::success);
    }

    @PostMapping("/verify")
    public Mono<ApiResponse<Boolean>> verifyMessage(@RequestBody MessageVerificationRequest request) {
        return crossChainBridgeService.verifyMessage(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/transfers/{transferId}/mint")
    public Mono<ApiResponse<CrossChainTransfer>> mintAssets(@PathVariable String transferId) {
        return crossChainBridgeService.mintAssets(transferId)
                .map(ApiResponse::success);
    }

    @PostMapping("/transfers/{transferId}/execute")
    public Mono<ApiResponse<CrossChainTransfer>> executeTransfer(@PathVariable String transferId) {
        return crossChainBridgeService.executeTransfer(transferId)
                .map(ApiResponse::success);
    }

    @GetMapping("/transfers/{transferId}")
    public Mono<ApiResponse<CrossChainTransfer>> getTransfer(@PathVariable String transferId) {
        return crossChainBridgeService.getTransfer(transferId)
                .map(ApiResponse::success);
    }

    @GetMapping("/transfers/source/{chain}/{txHash}")
    public Mono<ApiResponse<CrossChainTransfer>> getTransferBySourceTx(
            @PathVariable String chain,
            @PathVariable String txHash) {
        return crossChainBridgeService.getTransferBySourceTx(chain, txHash)
                .map(ApiResponse::success);
    }

    @GetMapping("/transfers/target/{chain}/{txHash}")
    public Mono<ApiResponse<CrossChainTransfer>> getTransferByTargetTx(
            @PathVariable String chain,
            @PathVariable String txHash) {
        return crossChainBridgeService.getTransferByTargetTx(chain, txHash)
                .map(ApiResponse::success);
    }
}
