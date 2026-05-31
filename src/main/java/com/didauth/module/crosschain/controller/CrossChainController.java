package com.didauth.module.crosschain.controller;

import com.didauth.common.response.ApiResponse;
import com.didauth.core.entity.CrossChainBridge;
import com.didauth.core.entity.CrossChainTransfer;
import com.didauth.module.crosschain.dto.InitiateTransferRequest;
import com.didauth.module.crosschain.service.CrossChainService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/crosschain")
@RequiredArgsConstructor
public class CrossChainController {

    private final CrossChainService crossChainService;

    @PostMapping("/bridges")
    public Mono<ApiResponse<String>> registerBridge(@RequestBody Map<String, String> request) {
        return crossChainService.registerBridge(
                request.get("sourceChain"),
                request.get("targetChain"),
                request.get("assetSymbol"),
                request.get("assetAddress"),
                request.get("bridgeContract")
        ).map(id -> ApiResponse.success(201, id));
    }

    @GetMapping("/bridges")
    public Mono<ApiResponse<List<CrossChainBridge>>> listBridges(
            @RequestParam(required = false) String sourceChain,
            @RequestParam(required = false) String targetChain,
            @RequestParam(required = false) String assetSymbol) {
        return crossChainService.listBridges(sourceChain, targetChain, assetSymbol)
                .map(ApiResponse::success);
    }

    @PostMapping("/transfers")
    public Mono<ApiResponse<String>> initiateTransfer(@Valid @RequestBody InitiateTransferRequest request) {
        return crossChainService.initiateTransfer(request)
                .map(id -> ApiResponse.success(201, id));
    }

    @PostMapping("/transfers/{transferId}/confirm-source")
    public Mono<ApiResponse<String>> confirmSourceTransaction(
            @PathVariable String transferId,
            @RequestBody Map<String, String> request) {
        return crossChainService.confirmSourceTransaction(transferId, request.get("sourceTxHash"))
                .map(ApiResponse::success);
    }

    @PostMapping("/transfers/{transferId}/verify-proof")
    public Mono<ApiResponse<String>> verifyMessageProof(
            @PathVariable String transferId,
            @RequestBody Map<String, String> request) {
        return crossChainService.verifyMessageProof(transferId, request.get("messageProof"))
                .map(ApiResponse::success);
    }

    @PostMapping("/transfers/{transferId}/execute-mint")
    public Mono<ApiResponse<String>> executeMint(
            @PathVariable String transferId,
            @RequestBody Map<String, String> request) {
        return crossChainService.executeMint(transferId, request.get("targetTxHash"))
                .map(ApiResponse::success);
    }

    @GetMapping("/transfers/{transferId}")
    public Mono<ApiResponse<CrossChainTransfer>> getTransferStatus(@PathVariable String transferId) {
        return crossChainService.getTransferStatus(transferId)
                .map(ApiResponse::success);
    }

    @GetMapping("/transfers")
    public Mono<ApiResponse<List<CrossChainTransfer>>> listTransfers(
            @RequestParam(required = false) String bridgeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sourceChain,
            @RequestParam(required = false) String targetChain) {
        return crossChainService.listTransfers(bridgeId, status, sourceChain, targetChain)
                .map(ApiResponse::success);
    }
}
