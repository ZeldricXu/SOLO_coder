package com.chain.infrastructure.chainindexer.controller;

import com.chain.infrastructure.common.dto.ApiResponse;
import com.chain.infrastructure.chainindexer.dto.BlockData;
import com.chain.infrastructure.chainindexer.service.BlockIndexerService;
import com.chain.infrastructure.persistence.entity.IndexedBlock;
import com.chain.infrastructure.persistence.entity.IndexedTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/indexer")
@RequiredArgsConstructor
public class BlockIndexerController {

    private final BlockIndexerService blockIndexerService;

    @PostMapping("/blocks")
    public Mono<ApiResponse<IndexedBlock>> indexBlock(@RequestBody BlockData blockData) {
        return blockIndexerService.indexBlock(blockData)
                .map(ApiResponse::created);
    }

    @GetMapping("/blocks/{chainType}/{blockNumber}")
    public Mono<ApiResponse<IndexedBlock>> getBlock(
            @PathVariable String chainType,
            @PathVariable Long blockNumber) {
        return blockIndexerService.getBlockByNumber(chainType, blockNumber)
                .map(ApiResponse::success);
    }

    @GetMapping("/blocks/{chainType}/latest")
    public Mono<ApiResponse<Long>> getLatestBlockNumber(@PathVariable String chainType) {
        return blockIndexerService.getLatestBlockNumber(chainType)
                .map(ApiResponse::success);
    }

    @GetMapping("/blocks/{chainType}/{blockNumber}/transactions")
    public Flux<IndexedTransaction> getBlockTransactions(
            @PathVariable String chainType,
            @PathVariable Long blockNumber) {
        return blockIndexerService.getTransactionsByBlock(chainType, blockNumber);
    }

    @GetMapping("/transactions/{chainType}/address/{address}")
    public Flux<IndexedTransaction> getAddressTransactions(
            @PathVariable String chainType,
            @PathVariable String address) {
        return blockIndexerService.getTransactionsByAddress(chainType, address);
    }
}
