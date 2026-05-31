package com.chainetl.modules.chain.controller;

import com.chainetl.common.dto.ApiResponse;
import com.chainetl.modules.chain.dto.BlockData;
import com.chainetl.modules.chain.dto.RpcNodeConfig;
import com.chainetl.modules.chain.dto.SubmitTransactionRequest;
import com.chainetl.modules.chain.dto.TransactionData;
import com.chainetl.modules.chain.model.ChainNode;
import com.chainetl.modules.chain.service.ChainAdapterService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/chains")
@RequiredArgsConstructor
public class ChainAdapterController {

    private final ChainAdapterService chainAdapterService;

    @GetMapping("/{chainId}/blocks/{blockNumber}")
    @Timed(value = "chain.block.getByNumber", description = "Time taken to get block by number")
    public Mono<ResponseEntity<ApiResponse<BlockData>>> getBlockByNumber(
            @PathVariable String chainId,
            @PathVariable Long blockNumber) {
        return chainAdapterService.getBlockByNumber(chainId, blockNumber)
                .map(block -> ResponseEntity.ok(ApiResponse.success(block)));
    }

    @GetMapping("/{chainId}/blocks/hash/{blockHash}")
    @Timed(value = "chain.block.getByHash", description = "Time taken to get block by hash")
    public Mono<ResponseEntity<ApiResponse<BlockData>>> getBlockByHash(
            @PathVariable String chainId,
            @PathVariable String blockHash) {
        return chainAdapterService.getBlockByHash(chainId, blockHash)
                .map(block -> ResponseEntity.ok(ApiResponse.success(block)));
    }

    @GetMapping("/{chainId}/transactions/{txHash}")
    @Timed(value = "chain.transaction.get", description = "Time taken to get transaction")
    public Mono<ResponseEntity<ApiResponse<TransactionData>>> getTransactionByHash(
            @PathVariable String chainId,
            @PathVariable String txHash) {
        return chainAdapterService.getTransactionByHash(chainId, txHash)
                .map(tx -> ResponseEntity.ok(ApiResponse.success(tx)));
    }

    @PostMapping("/{chainId}/transactions")
    @Timed(value = "chain.transaction.submit", description = "Time taken to submit transaction")
    public Mono<ResponseEntity<ApiResponse<Map<String, String>>>> submitTransaction(
            @PathVariable String chainId,
            @Valid @RequestBody SubmitTransactionRequest request) {
        request.setChainId(chainId);
        return chainAdapterService.submitTransaction(request)
                .map(txHash -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success(201, Map.of("txHash", txHash))));
    }

    @GetMapping("/{chainId}/blocks/latest")
    @Timed(value = "chain.block.getLatest", description = "Time taken to get latest block")
    public Mono<ResponseEntity<ApiResponse<Map<String, BigInteger>>>> getLatestBlockNumber(
            @PathVariable String chainId) {
        return chainAdapterService.getLatestBlockNumber(chainId)
                .map(blockNumber -> ResponseEntity.ok(ApiResponse.success(Map.of("blockNumber", blockNumber))));
    }

    @PostMapping("/nodes")
    @Timed(value = "chain.node.register", description = "Time taken to register a chain node")
    public Mono<ResponseEntity<ApiResponse<ChainNode>>> registerNode(
            @Valid @RequestBody RpcNodeConfig config) {
        return chainAdapterService.registerNode(config)
                .map(node -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success(201, node)));
    }

    @GetMapping("/nodes")
    @Timed(value = "chain.node.list", description = "Time taken to list chain nodes")
    public Mono<ResponseEntity<ApiResponse<List<ChainNode>>>> listNodes(
            @RequestParam(required = false) String chainId) {
        return chainAdapterService.listNodes(chainId)
                .map(nodes -> ResponseEntity.ok(ApiResponse.success(nodes)));
    }

    @GetMapping("/{chainId}/transactions/{txHash}/receipt")
    @Timed(value = "chain.transaction.getReceipt", description = "Time taken to get transaction receipt")
    public Mono<ResponseEntity<ApiResponse<Map<String, String>>>> getTransactionReceipt(
            @PathVariable String chainId,
            @PathVariable String txHash) {
        return chainAdapterService.getTransactionReceipt(chainId, txHash)
                .map(receipt -> ResponseEntity.ok(ApiResponse.success(Map.of("receipt", receipt))));
    }
}
