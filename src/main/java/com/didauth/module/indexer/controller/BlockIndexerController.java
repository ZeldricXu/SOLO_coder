package com.didauth.module.indexer.controller;

import com.didauth.common.response.ApiResponse;
import com.didauth.core.entity.BlockIndex;
import com.didauth.core.entity.TransactionIndex;
import com.didauth.module.indexer.dto.BlockParseRequest;
import com.didauth.module.indexer.service.BlockIndexerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/indexer")
@RequiredArgsConstructor
public class BlockIndexerController {

    private final BlockIndexerService blockIndexerService;

    @PostMapping("/blocks")
    public Mono<ApiResponse<String>> parseBlock(@Valid @RequestBody BlockParseRequest request) {
        return blockIndexerService.parseAndIndexBlock(request)
                .map(id -> ApiResponse.success(201, id));
    }

    @GetMapping("/blocks/{chainType}/{blockNumber}")
    public Mono<ApiResponse<BlockIndex>> getBlock(
            @PathVariable String chainType,
            @PathVariable Long blockNumber) {
        return blockIndexerService.getBlockByNumber(chainType, blockNumber)
                .map(ApiResponse::success);
    }

    @GetMapping("/blocks/{chainType}/latest")
    public Mono<ApiResponse<List<BlockIndex>>> getLatestBlocks(
            @PathVariable String chainType,
            @RequestParam(defaultValue = "10") Integer limit) {
        return blockIndexerService.getLatestBlocks(chainType, limit)
                .map(ApiResponse::success);
    }

    @GetMapping("/transactions/{chainType}/{txHash}")
    public Mono<ApiResponse<TransactionIndex>> getTransaction(
            @PathVariable String chainType,
            @PathVariable String txHash) {
        return blockIndexerService.getTransactionByHash(chainType, txHash)
                .map(ApiResponse::success);
    }

    @GetMapping("/addresses/{chainType}/{address}/transactions")
    public Flux<TransactionIndex> getTransactionsByAddress(
            @PathVariable String chainType,
            @PathVariable String address,
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(defaultValue = "0") Integer offset) {
        return blockIndexerService.getTransactionsByAddress(chainType, address, limit, offset);
    }
}
