package com.nftindexer.modules.gas.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nftindexer.common.ApiResponse;
import com.nftindexer.common.PageResult;
import com.nftindexer.entity.GasEstimate;
import com.nftindexer.entity.GasHistory;
import com.nftindexer.modules.gas.dto.GasEstimateRequest;
import com.nftindexer.modules.gas.dto.GasHistoryRecordRequest;
import com.nftindexer.modules.gas.service.GasService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/gas")
@RequiredArgsConstructor
public class GasController {

    private final GasService gasService;

    @PostMapping("/estimate")
    public Mono<ApiResponse<GasEstimate>> estimateGas(
            @Valid @RequestBody GasEstimateRequest request) {
        return gasService.estimateGas(request)
                .map(estimate -> ApiResponse.created(estimate));
    }

    @GetMapping("/estimate/{chainId}/latest")
    public Mono<ApiResponse<GasEstimate>> getLatestGasEstimate(@PathVariable String chainId) {
        return gasService.getLatestGasEstimate(chainId)
                .map(ApiResponse::success);
    }

    @PostMapping("/estimate/batch")
    public Mono<ApiResponse<Map<String, GasEstimate>>> getLatestGasEstimates(
            @RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> chainIds = (List<String>) request.getOrDefault("chainIds", List.of());
        return gasService.getLatestGasEstimates(chainIds)
                .map(ApiResponse::success);
    }

    @PostMapping("/history")
    public Mono<ApiResponse<GasHistory>> recordGasHistory(
            @Valid @RequestBody GasHistoryRecordRequest request) {
        return gasService.recordGasHistory(request)
                .map(history -> ApiResponse.created(history));
    }

    @GetMapping("/history/{chainId}")
    public Mono<ApiResponse<PageResult<GasHistory>>> getGasHistory(
            @PathVariable String chainId,
            @RequestParam(required = false) Integer startBlock,
            @RequestParam(required = false) Integer endBlock,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return gasService.getGasHistory(chainId, startBlock, endBlock, pageNum, pageSize)
                .map(page -> ApiResponse.success(new PageResult<>(
                        page.getRecords(),
                        page.getTotal(),
                        page.getCurrent(),
                        page.getSize()
                )));
    }

    @GetMapping("/statistics/{chainId}")
    public Mono<ApiResponse<Map<String, Object>>> getGasStatistics(
            @PathVariable String chainId,
            @RequestParam(defaultValue = "100") int blocks) {
        return gasService.getGasStatistics(chainId, blocks)
                .map(ApiResponse::success);
    }

    @GetMapping("/suggest/{chainId}")
    public Mono<ApiResponse<Map<String, BigInteger>>> suggestGasPrice(
            @PathVariable String chainId,
            @RequestParam(defaultValue = "2") int priorityLevel) {
        return gasService.suggestGasPrice(chainId, priorityLevel)
                .map(ApiResponse::success);
    }
}
