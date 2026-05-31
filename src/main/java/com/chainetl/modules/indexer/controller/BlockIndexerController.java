package com.chainetl.modules.indexer.controller;

import com.chainetl.common.dto.ApiResponse;
import com.chainetl.modules.indexer.dto.IndexBlockRequest;
import com.chainetl.modules.indexer.dto.IndexRangeRequest;
import com.chainetl.modules.indexer.dto.IndexedBlockResponse;
import com.chainetl.modules.indexer.dto.IndexedTransactionResponse;
import com.chainetl.modules.indexer.dto.IndexerStatusResponse;
import com.chainetl.modules.indexer.service.BlockIndexerService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/indexer")
@RequiredArgsConstructor
public class BlockIndexerController {

    private final BlockIndexerService indexerService;

    @PostMapping("/blocks")
    @Timed(value = "indexer.block.index", description = "Time taken to index a block")
    public Mono<ResponseEntity<ApiResponse<IndexedBlockResponse>>> indexBlock(
            @Valid @RequestBody IndexBlockRequest request) {
        return indexerService.indexBlock(request)
                .map(block -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success(201, block)));
    }

    @PostMapping("/blocks/range")
    @Timed(value = "indexer.block.range", description = "Time taken to start range indexing")
    public Mono<ResponseEntity<ApiResponse<Map<String, String>>>> indexBlockRange(
            @Valid @RequestBody IndexRangeRequest request) {
        return indexerService.indexBlockRange(request)
                .map(runId -> ResponseEntity.accepted()
                        .body(ApiResponse.success(202, Map.of(
                                "runId", runId,
                                "status", "STARTED"
                        ))));
    }

    @GetMapping("/{chainId}/blocks/{blockNumber}")
    @Timed(value = "indexer.block.get.number", description = "Time taken to get block by number")
    public Mono<ResponseEntity<ApiResponse<IndexedBlockResponse>>> getBlockByNumber(
            @PathVariable String chainId,
            @PathVariable Long blockNumber) {
        return indexerService.getBlockByNumber(chainId, blockNumber)
                .map(block -> ResponseEntity.ok(ApiResponse.success(block)));
    }

    @GetMapping("/{chainId}/blocks/hash/{blockHash}")
    @Timed(value = "indexer.block.get.hash", description = "Time taken to get block by hash")
    public Mono<ResponseEntity<ApiResponse<IndexedBlockResponse>>> getBlockByHash(
            @PathVariable String chainId,
            @PathVariable String blockHash) {
        return indexerService.getBlockByHash(chainId, blockHash)
                .map(block -> ResponseEntity.ok(ApiResponse.success(block)));
    }

    @GetMapping("/{chainId}/transactions/{txHash}")
    @Timed(value = "indexer.tx.get", description = "Time taken to get transaction")
    public Mono<ResponseEntity<ApiResponse<IndexedTransactionResponse>>> getTransactionByHash(
            @PathVariable String chainId,
            @PathVariable String txHash) {
        return indexerService.getTransactionByHash(chainId, txHash)
                .map(tx -> ResponseEntity.ok(ApiResponse.success(tx)));
    }

    @GetMapping("/{chainId}/addresses/{address}/transactions")
    @Timed(value = "indexer.address.tx", description = "Time taken to get transactions by address")
    public Mono<ResponseEntity<ApiResponse<List<IndexedTransactionResponse>>>> getTransactionsByAddress(
            @PathVariable String chainId,
            @PathVariable String address,
            @RequestParam(required = false, defaultValue = "100") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset) {
        return indexerService.getTransactionsByAddress(chainId, address, limit, offset)
                .map(txs -> ResponseEntity.ok(ApiResponse.success(txs)));
    }

    @GetMapping("/{chainId}/blocks")
    @Timed(value = "indexer.blocks.list", description = "Time taken to list blocks")
    public Mono<ResponseEntity<ApiResponse<List<IndexedBlockResponse>>>> listBlocks(
            @PathVariable String chainId,
            @RequestParam(required = false) Long startBlock,
            @RequestParam(required = false) Long endBlock,
            @RequestParam(required = false, defaultValue = "100") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset) {
        return indexerService.listBlocks(chainId, startBlock, endBlock, limit, offset)
                .map(blocks -> ResponseEntity.ok(ApiResponse.success(blocks)));
    }

    @GetMapping("/{chainId}/status")
    @Timed(value = "indexer.status.get", description = "Time taken to get indexer status")
    public Mono<ResponseEntity<ApiResponse<IndexerStatusResponse>>> getIndexerStatus(
            @PathVariable String chainId) {
        return indexerService.getIndexerStatus(chainId)
                .map(status -> ResponseEntity.ok(ApiResponse.success(status)));
    }

    @GetMapping("/metrics")
    @Timed(value = "indexer.metrics.get", description = "Time taken to get global indexer metrics")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> getGlobalMetrics() {
        return indexerService.getGlobalMetrics()
                .map(metrics -> ResponseEntity.ok(ApiResponse.success(metrics)));
    }

    @DeleteMapping("/{chainId}/blocks/{blockNumber}")
    @Timed(value = "indexer.block.delete", description = "Time taken to delete block index")
    public Mono<ResponseEntity<ApiResponse<Void>>> deleteBlock(
            @PathVariable String chainId,
            @PathVariable Long blockNumber) {
        return indexerService.deleteBlock(chainId, blockNumber)
                .then(Mono.just(ResponseEntity.ok(ApiResponse.success(null))));
    }
}
