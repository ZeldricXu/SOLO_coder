package com.nftindexer.modules.bridge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nftindexer.common.ApiResponse;
import com.nftindexer.common.PageResult;
import com.nftindexer.entity.CrossChainBridge;
import com.nftindexer.entity.CrossChainMessage;
import com.nftindexer.modules.bridge.dto.BridgeInitiateRequest;
import com.nftindexer.modules.bridge.dto.BridgeStatusResponse;
import com.nftindexer.modules.bridge.dto.MessageVerifyRequest;
import com.nftindexer.modules.bridge.service.CrossChainBridgeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/bridge")
@RequiredArgsConstructor
public class CrossChainBridgeController {

    private final CrossChainBridgeService bridgeService;

    @PostMapping("/initiate")
    public Mono<ApiResponse<CrossChainBridge>> initiateBridge(
            @Valid @RequestBody BridgeInitiateRequest request) {
        return bridgeService.initiateBridge(request)
                .map(bridge -> ApiResponse.created(bridge));
    }

    @PostMapping("/verify")
    public Mono<ApiResponse<CrossChainMessage>> verifyMessage(
            @Valid @RequestBody MessageVerifyRequest request) {
        return bridgeService.verifyMessage(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/{bridgeId}/mint")
    public Mono<ApiResponse<CrossChainBridge>> executeMint(
            @PathVariable String bridgeId,
            @RequestBody Map<String, Object> request) {
        String targetTxHash = (String) request.get("targetTxHash");
        BigInteger targetTokenId = request.get("targetTokenId") != null ?
                new BigInteger(request.get("targetTokenId").toString()) : null;
        return bridgeService.executeMint(bridgeId, targetTxHash, targetTokenId)
                .map(ApiResponse::success);
    }

    @GetMapping("/{bridgeId}/status")
    public Mono<ApiResponse<BridgeStatusResponse>> getBridgeStatus(@PathVariable String bridgeId) {
        return bridgeService.getBridgeStatus(bridgeId)
                .map(ApiResponse::success);
    }

    @GetMapping
    public Mono<ApiResponse<PageResult<CrossChainBridge>>> listBridges(
            @RequestParam(required = false) String sourceChain,
            @RequestParam(required = false) String targetChain,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return bridgeService.listBridges(sourceChain, targetChain, status, pageNum, pageSize)
                .map(page -> ApiResponse.success(new PageResult<>(
                        page.getRecords(),
                        page.getTotal(),
                        page.getCurrent(),
                        page.getSize()
                )));
    }

    @PostMapping("/{bridgeId}/confirmations")
    public Mono<ApiResponse<CrossChainBridge>> updateConfirmations(
            @PathVariable String bridgeId,
            @RequestBody Map<String, Integer> request) {
        int confirmations = request.getOrDefault("confirmations", 0);
        return bridgeService.updateConfirmations(bridgeId, confirmations)
                .map(ApiResponse::success);
    }

    @PostMapping("/{bridgeId}/cancel")
    public Mono<ApiResponse<Void>> cancelBridge(
            @PathVariable String bridgeId,
            @RequestBody(required = false) Map<String, String> request) {
        String reason = request != null ? request.getOrDefault("reason", "用户取消") : "用户取消";
        return bridgeService.cancelBridge(bridgeId, reason)
                .then(Mono.just(ApiResponse.success()));
    }
}
