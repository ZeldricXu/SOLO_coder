package com.chainetl.modules.gas.controller;

import com.chainetl.common.dto.ApiResponse;
import com.chainetl.modules.gas.dto.GasEstimateRequest;
import com.chainetl.modules.gas.dto.GasEstimateResponse;
import com.chainetl.modules.gas.service.GasEstimationService;
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
@RequestMapping("/api/v1/gas")
@RequiredArgsConstructor
public class GasEstimationController {

    private final GasEstimationService gasEstimationService;

    @PostMapping("/estimate")
    @Timed(value = "gas.estimate.request", description = "Time taken to handle gas estimate request")
    public Mono<ResponseEntity<ApiResponse<GasEstimateResponse>>> estimateGas(
            @Valid @RequestBody GasEstimateRequest request) {
        return gasEstimationService.estimateGas(request)
                .map(estimate -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success(201, estimate)));
    }

    @GetMapping("/estimates/{estimateId}")
    @Timed(value = "gas.estimate.get", description = "Time taken to get gas estimate")
    public Mono<ResponseEntity<ApiResponse<GasEstimateResponse>>> getEstimate(
            @PathVariable String estimateId) {
        return gasEstimationService.getEstimate(estimateId)
                .map(estimate -> ResponseEntity.ok(ApiResponse.success(estimate)));
    }

    @GetMapping("/estimates")
    @Timed(value = "gas.estimate.list", description = "Time taken to list gas estimates")
    public Mono<ResponseEntity<ApiResponse<List<GasEstimateResponse>>>> listEstimates(
            @RequestParam(required = false) String chainId,
            @RequestParam(required = false) String transactionType,
            @RequestParam(required = false, defaultValue = "100") Integer limit) {
        return gasEstimationService.listEstimates(chainId, transactionType, limit)
                .map(estimates -> ResponseEntity.ok(ApiResponse.success(estimates)));
    }

    @GetMapping("/oracle/{chainId}")
    @Timed(value = "gas.oracle.get", description = "Time taken to get gas oracle data")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> getGasPriceOracle(
            @PathVariable String chainId) {
        return gasEstimationService.getGasPriceOracle(chainId)
                .map(data -> ResponseEntity.ok(ApiResponse.success(data)));
    }

    @GetMapping("/historical/{chainId}")
    @Timed(value = "gas.historical.get", description = "Time taken to get historical gas data")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> getHistoricalGasData(
            @PathVariable String chainId,
            @RequestParam(required = false, defaultValue = "24") Integer hours) {
        return gasEstimationService.getHistoricalGasData(chainId, hours)
                .map(data -> ResponseEntity.ok(ApiResponse.success(data)));
    }

    @PostMapping("/cost")
    @Timed(value = "gas.cost.calculate", description = "Time taken to calculate transaction cost")
    public Mono<ResponseEntity<ApiResponse<Map<String, Long>>>> calculateTransactionCost(
            @Valid @RequestBody GasEstimateRequest request,
            @RequestParam(required = false, defaultValue = "MEDIUM") String speedLevel) {
        return gasEstimationService.calculateTransactionCost(request, speedLevel)
                .map(cost -> ResponseEntity.ok(ApiResponse.success(cost)));
    }
}
