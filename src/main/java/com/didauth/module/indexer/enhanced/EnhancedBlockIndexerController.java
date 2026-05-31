package com.didauth.module.indexer.enhanced;

import com.didauth.common.response.ApiResponse;
import com.didauth.core.entity.BlockIndex;
import com.didauth.module.indexer.dto.BlockParseRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/indexer")
@RequiredArgsConstructor
public class EnhancedBlockIndexerController {

    private final EnhancedBlockIndexerService enhancedBlockIndexerService;

    @PostMapping("/blocks")
    public Mono<ApiResponse<String>> parseBlock(@Valid @RequestBody BlockParseRequest request) {
        return enhancedBlockIndexerService.parseAndIndexBlock(request)
                .map(id -> ApiResponse.success(201, id));
    }

    @GetMapping("/blocks/{chainType}/{blockNumber}")
    public Mono<ApiResponse<BlockIndex>> getBlock(
            @PathVariable String chainType,
            @PathVariable Long blockNumber) {
        return enhancedBlockIndexerService.getBlockByNumber(chainType, blockNumber)
                .map(ApiResponse::success);
    }

    @GetMapping("/status/{chainType}")
    public Mono<ApiResponse<Map<String, Object>>> getIndexerStatus(@PathVariable String chainType) {
        return enhancedBlockIndexerService.getIndexerStatus(chainType)
                .map(ApiResponse::success);
    }

    @GetMapping("/status")
    public Mono<ApiResponse<Map<String, Object>>> getAllChainsStatus() {
        return enhancedBlockIndexerService.getAllChainsStatus()
                .map(ApiResponse::success);
    }

    @GetMapping("/metrics")
    public Mono<ApiResponse<IndexerMetrics>> getMetrics() {
        return Mono.just(ApiResponse.success(enhancedBlockIndexerService.getMetrics()));
    }
}
