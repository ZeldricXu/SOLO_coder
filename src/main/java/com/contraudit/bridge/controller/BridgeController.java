package com.contraudit.bridge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.contraudit.common.ApiResponse;
import com.contraudit.bridge.dto.InitiateTransferRequest;
import com.contraudit.bridge.entity.BridgeChain;
import com.contraudit.bridge.entity.BridgeTransfer;
import com.contraudit.bridge.service.BridgeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/bridge")
@RequiredArgsConstructor
public class BridgeController {

    private final BridgeService bridgeService;

    @PostMapping("/transfers")
    public Mono<ApiResponse<BridgeTransfer>> initiateTransfer(@Valid @RequestBody InitiateTransferRequest request) {
        return Mono.just(ApiResponse.created(bridgeService.initiateTransfer(request)));
    }

    @GetMapping("/transfers/{transferId}")
    public Mono<ApiResponse<BridgeTransfer>> getTransfer(@PathVariable String transferId) {
        return Mono.just(ApiResponse.success(bridgeService.getTransfer(transferId)));
    }

    @GetMapping("/transfers")
    public Mono<ApiResponse<IPage<BridgeTransfer>>> listTransfers(
            @RequestParam(required = false) Long fromChainId,
            @RequestParam(required = false) Long toChainId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String address,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Mono.just(ApiResponse.success(
                bridgeService.listTransfers(fromChainId, toChainId, status, address, page, size)));
    }

    @PostMapping("/cache/clear")
    public Mono<ApiResponse<Void>> clearCaches() {
        bridgeService.clearAllCaches();
        return Mono.just(ApiResponse.success(null));
    }

    @GetMapping("/cache/stats")
    public Mono<ApiResponse<Map<String, Object>>> getCacheStats() {
        return Mono.just(ApiResponse.success(Map.of(
                "message", "Cache stats logged",
                "timestamp", System.currentTimeMillis()
        )));
    }

    @PostMapping("/transfers/{transferId}/lock")
    public Mono<ApiResponse<BridgeTransfer>> confirmLock(
            @PathVariable String transferId,
            @RequestParam String txHash,
            @RequestParam Long blockNumber) {
        return Mono.just(ApiResponse.success(bridgeService.confirmLock(transferId, txHash, blockNumber)));
    }

    @PostMapping("/transfers/{transferId}/mint")
    public Mono<ApiResponse<BridgeTransfer>> confirmMint(
            @PathVariable String transferId,
            @RequestParam String txHash,
            @RequestParam Long blockNumber,
            @RequestParam(required = false) String proofData) {
        return Mono.just(ApiResponse.success(bridgeService.confirmMint(transferId, txHash, blockNumber, proofData)));
    }

    @PostMapping("/transfers/{transferId}/complete")
    public Mono<ApiResponse<BridgeTransfer>> completeTransfer(@PathVariable String transferId) {
        return Mono.just(ApiResponse.success(bridgeService.completeTransfer(transferId)));
    }

    @PostMapping("/transfers/{transferId}/fail")
    public Mono<ApiResponse<BridgeTransfer>> failTransfer(
            @PathVariable String transferId,
            @RequestParam String errorMessage) {
        return Mono.just(ApiResponse.success(bridgeService.failTransfer(transferId, errorMessage)));
    }

    @GetMapping("/chains")
    public Mono<ApiResponse<List<BridgeChain>>> listSupportedChains() {
        return Mono.just(ApiResponse.success(bridgeService.listSupportedChains()));
    }

    @GetMapping("/chains/{chainId}")
    public Mono<ApiResponse<BridgeChain>> getChain(@PathVariable Long chainId) {
        return Mono.just(ApiResponse.success(bridgeService.getChain(chainId)));
    }

    @PostMapping("/messages/{messageId}/verify")
    public Mono<ApiResponse<Boolean>> verifyMessage(
            @PathVariable String messageId,
            @RequestParam String signature) {
        return Mono.just(ApiResponse.success(bridgeService.verifyMessage(messageId, signature)));
    }
}
