package com.chain.infrastructure.chainadapter.controller;

import com.chain.infrastructure.chainadapter.dto.SubmitTransactionRequest;
import com.chain.infrastructure.chainadapter.service.ChainRpcService;
import com.chain.infrastructure.common.dto.ApiResponse;
import com.chain.infrastructure.persistence.entity.RpcNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;

@RestController
@RequestMapping("/api/v1/chain")
@RequiredArgsConstructor
public class ChainRpcController {

    private final ChainRpcService chainRpcService;

    @GetMapping("/{chainType}/block-number")
    public Mono<ApiResponse<String>> getBlockNumber(@PathVariable String chainType) {
        return chainRpcService.getBlockNumber(chainType)
                .map(ApiResponse::success);
    }

    @GetMapping("/{chainType}/gas-price")
    public Mono<ApiResponse<String>> getGasPrice(@PathVariable String chainType) {
        return chainRpcService.getGasPrice(chainType)
                .map(ApiResponse::success);
    }

    @GetMapping("/{chainType}/nonce/{address}")
    public Mono<ApiResponse<String>> getNonce(
            @PathVariable String chainType,
            @PathVariable String address) {
        return chainRpcService.getTransactionCount(chainType, address)
                .map(ApiResponse::success);
    }

    @PostMapping("/transactions/submit")
    public Mono<ApiResponse<String>> submitTransaction(@RequestBody SubmitTransactionRequest request) {
        return chainRpcService.submitTransaction(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/{chainType}/transactions/{txHash}/receipt")
    public Mono<ApiResponse<String>> getTransactionReceipt(
            @PathVariable String chainType,
            @PathVariable String txHash) {
        return chainRpcService.getTransactionReceipt(chainType, txHash)
                .map(ApiResponse::success);
    }

    @GetMapping("/{chainType}/blocks/{blockNumber}")
    public Mono<ApiResponse<String>> getBlock(
            @PathVariable String chainType,
            @PathVariable Long blockNumber,
            @RequestParam(defaultValue = "false") boolean fullTx) {
        return chainRpcService.getBlockByNumber(chainType, blockNumber, fullTx)
                .map(ApiResponse::success);
    }

    @PostMapping("/nodes")
    public Mono<ApiResponse<RpcNode>> registerNode(@RequestBody RpcNode node) {
        return chainRpcService.registerNode(node)
                .map(ApiResponse::created);
    }

    @GetMapping("/nodes/{chainType}")
    public Mono<ApiResponse<List<RpcNode>>> getNodes(@PathVariable String chainType) {
        return chainRpcService.getNodes(chainType)
                .map(ApiResponse::success);
    }

    @PostMapping("/nodes/health-check")
    public Mono<ApiResponse<Void>> healthCheck() {
        return chainRpcService.healthCheckAllNodes()
                .map(ApiResponse::success);
    }
}
