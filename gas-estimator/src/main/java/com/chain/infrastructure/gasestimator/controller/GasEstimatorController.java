package com.chain.infrastructure.gasestimator.controller;

import com.chain.infrastructure.common.dto.ApiResponse;
import com.chain.infrastructure.gasestimator.dto.BatchGasEstimateRequest;
import com.chain.infrastructure.gasestimator.dto.BatchGasEstimateResult;
import com.chain.infrastructure.gasestimator.dto.GasEstimateRequest;
import com.chain.infrastructure.gasestimator.dto.GasEstimateResult;
import com.chain.infrastructure.gasestimator.service.GasEstimatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/gas")
@RequiredArgsConstructor
public class GasEstimatorController {

    private final GasEstimatorService gasEstimatorService;

    @PostMapping("/estimate")
    public Mono<ApiResponse<GasEstimateResult>> estimateGas(@RequestBody GasEstimateRequest request) {
        return gasEstimatorService.estimateGas(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/estimate/batch")
    public Mono<ApiResponse<BatchGasEstimateResult>> estimateBatch(@RequestBody BatchGasEstimateRequest request) {
        return gasEstimatorService.estimateBatch(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/estimate/batched")
    public Mono<ApiResponse<GasEstimateResult>> estimateBatched(@RequestBody GasEstimateRequest request) {
        return gasEstimatorService.estimateGasBatched(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/estimate/chains")
    public Mono<ApiResponse<List<GasEstimateResult>>> estimateByChains(
            @RequestParam List<String> chains,
            @RequestParam(defaultValue = "TRANSFER") String txType) {
        return gasEstimatorService.estimateGasByChains(chains, txType)
                .map(ApiResponse::success);
    }

    @GetMapping("/{chainType}/current")
    public Mono<ApiResponse<GasEstimateResult>> getCurrentGasPrice(@PathVariable String chainType) {
        GasEstimateRequest request = new GasEstimateRequest();
        request.setChainType(chainType);
        return gasEstimatorService.estimateGas(request)
                .map(ApiResponse::success);
    }
}
