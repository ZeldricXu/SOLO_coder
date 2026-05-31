package com.nftindexer.modules.indexer.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nftindexer.common.ApiResponse;
import com.nftindexer.common.PageResult;
import com.nftindexer.entity.ChainBlock;
import com.nftindexer.entity.ChainIndexerState;
import com.nftindexer.entity.ChainTransaction;
import com.nftindexer.entity.NftMetadata;
import com.nftindexer.modules.indexer.dto.BlockIndexRequest;
import com.nftindexer.modules.indexer.dto.NftMetadataIndexRequest;
import com.nftindexer.modules.indexer.dto.TransactionIndexRequest;
import com.nftindexer.modules.indexer.service.ChainIndexerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/indexer")
@RequiredArgsConstructor
public class ChainIndexerController {

    private final ChainIndexerService indexerService;

    @PostMapping("/blocks")
    public Mono<ApiResponse<ChainBlock>> indexBlock(
            @Valid @RequestBody BlockIndexRequest request) {
        return indexerService.indexBlock(request)
                .map(block -> ApiResponse.created(block));
    }

    @GetMapping("/blocks/{chainId}/{blockNumber}")
    public Mono<ApiResponse<ChainBlock>> getBlock(
            @PathVariable String chainId,
            @PathVariable Integer blockNumber) {
        return indexerService.getBlock(chainId, blockNumber)
                .map(ApiResponse::success);
    }

    @GetMapping("/blocks/{chainId}/hash/{blockHash}")
    public Mono<ApiResponse<ChainBlock>> getBlockByHash(
            @PathVariable String chainId,
            @PathVariable String blockHash) {
        return indexerService.getBlockByHash(chainId, blockHash)
                .map(ApiResponse::success);
    }

    @GetMapping("/blocks/{chainId}")
    public Mono<ApiResponse<PageResult<ChainBlock>>> listBlocks(
            @PathVariable String chainId,
            @RequestParam(required = false) Integer startBlock,
            @RequestParam(required = false) Integer endBlock,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return indexerService.listBlocks(chainId, startBlock, endBlock, pageNum, pageSize)
                .map(page -> ApiResponse.success(new PageResult<>(
                        page.getRecords(),
                        page.getTotal(),
                        page.getCurrent(),
                        page.getSize()
                )));
    }

    @PostMapping("/transactions/{chainId}")
    public Mono<ApiResponse<ChainTransaction>> indexTransaction(
            @PathVariable String chainId,
            @Valid @RequestBody TransactionIndexRequest request) {
        return indexerService.indexTransaction(chainId, request)
                .map(tx -> ApiResponse.created(tx));
    }

    @GetMapping("/transactions/{chainId}/{txHash}")
    public Mono<ApiResponse<ChainTransaction>> getTransaction(
            @PathVariable String chainId,
            @PathVariable String txHash) {
        return indexerService.getTransaction(chainId, txHash)
                .map(ApiResponse::success);
    }

    @GetMapping("/transactions/{chainId}")
    public Mono<ApiResponse<PageResult<ChainTransaction>>> listTransactions(
            @PathVariable String chainId,
            @RequestParam(required = false) String fromAddress,
            @RequestParam(required = false) String toAddress,
            @RequestParam(required = false) String contractAddress,
            @RequestParam(required = false) Integer blockNumber,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return indexerService.listTransactions(chainId, fromAddress, toAddress,
                        contractAddress, blockNumber, pageNum, pageSize)
                .map(page -> ApiResponse.success(new PageResult<>(
                        page.getRecords(),
                        page.getTotal(),
                        page.getCurrent(),
                        page.getSize()
                )));
    }

    @PostMapping("/nfts")
    public Mono<ApiResponse<NftMetadata>> indexNftMetadata(
            @Valid @RequestBody NftMetadataIndexRequest request) {
        return indexerService.indexNftMetadata(request)
                .map(metadata -> ApiResponse.created(metadata));
    }

    @GetMapping("/nfts/{chainId}/{contractAddress}/{tokenId}")
    public Mono<ApiResponse<NftMetadata>> getNftMetadata(
            @PathVariable String chainId,
            @PathVariable String contractAddress,
            @PathVariable BigInteger tokenId) {
        return indexerService.getNftMetadata(chainId, contractAddress, tokenId)
                .map(ApiResponse::success);
    }

    @GetMapping("/nfts/id/{metadataId}")
    public Mono<ApiResponse<NftMetadata>> getNftMetadataById(@PathVariable String metadataId) {
        return indexerService.getNftMetadataById(metadataId)
                .map(ApiResponse::success);
    }

    @GetMapping("/nfts/search")
    public Mono<ApiResponse<PageResult<NftMetadata>>> searchNftMetadata(
            @RequestParam(required = false) String chainId,
            @RequestParam(required = false) String contractAddress,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return indexerService.searchNftMetadata(chainId, contractAddress, owner, name, pageNum, pageSize)
                .map(page -> ApiResponse.success(new PageResult<>(
                        page.getRecords(),
                        page.getTotal(),
                        page.getCurrent(),
                        page.getSize()
                )));
    }

    @GetMapping("/state/{chainId}/{indexerName}")
    public Mono<ApiResponse<ChainIndexerState>> getIndexerState(
            @PathVariable String chainId,
            @PathVariable String indexerName) {
        return indexerService.getIndexerState(chainId, indexerName)
                .map(ApiResponse::success);
    }

    @GetMapping("/state")
    public Mono<ApiResponse<List<ChainIndexerState>>> listIndexerStates(
            @RequestParam(required = false) String chainId) {
        return indexerService.listIndexerStates(chainId)
                .map(ApiResponse::success);
    }

    @GetMapping("/stats/{chainId}")
    public Mono<ApiResponse<Map<String, Object>>> getIndexerStats(@PathVariable String chainId) {
        return indexerService.getIndexerStats(chainId)
                .map(ApiResponse::success);
    }
}
