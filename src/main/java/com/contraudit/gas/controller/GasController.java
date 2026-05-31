package com.contraudit.gas.controller;

import com.contraudit.common.ApiResponse;
import com.contraudit.gas.entity.GasEstimation;
import com.contraudit.gas.entity.GasFeeHistory;
import com.contraudit.gas.entity.GasPriceOracle;
import com.contraudit.gas.service.GasService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/gas")
@RequiredArgsConstructor
public class GasController {

    private final GasService gasService;

    @PostMapping("/estimate")
    public Mono<ApiResponse<GasEstimation>> estimateGas(@RequestBody Map<String, Object> request) {
        String chainType = (String) request.getOrDefault("chainType", "ETH");
        String networkId = (String) request.getOrDefault("networkId", "1");
        String txType = (String) request.get("txType");

        BigDecimal estimatedGasLimit = request.get("estimatedGasLimit") != null ?
                new BigDecimal(request.get("estimatedGasLimit").toString()) : null;
        BigDecimal customBaseFee = request.get("customBaseFee") != null ?
                new BigDecimal(request.get("customBaseFee").toString()) : null;
        Integer historyHours = request.get("historyHours") != null ?
                ((Number) request.get("historyHours")).intValue() : null;

        return Mono.just(ApiResponse.success(
                gasService.estimateGas(chainType, networkId, txType,
                        estimatedGasLimit, customBaseFee, historyHours)));
    }

    @GetMapping("/estimations/{estimationId}")
    public Mono<ApiResponse<GasEstimation>> getEstimation(@PathVariable String estimationId) {
        return Mono.just(ApiResponse.success(gasService.getEstimation(estimationId)));
    }

    @GetMapping("/estimations")
    public Mono<ApiResponse<List<GasEstimation>>> listEstimations(
            @RequestParam(required = false) String chainType,
            @RequestParam(required = false) String networkId,
            @RequestParam(required = false) String txType) {
        return Mono.just(ApiResponse.success(
                gasService.listEstimations(chainType, networkId, txType)));
    }

    @PostMapping("/history")
    public Mono<ApiResponse<GasFeeHistory>> recordFeeHistory(@Valid @RequestBody GasFeeHistory history) {
        return Mono.just(ApiResponse.created(gasService.recordFeeHistory(history)));
    }

    @GetMapping("/history")
    public Mono<ApiResponse<List<GasFeeHistory>>> listFeeHistory(
            @RequestParam(required = false) String chainType,
            @RequestParam(required = false) String txType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toTime) {
        return Mono.just(ApiResponse.success(
                gasService.listFeeHistory(chainType, txType, fromTime, toTime)));
    }

    @PostMapping("/oracles")
    public Mono<ApiResponse<GasPriceOracle>> registerOracle(@Valid @RequestBody GasPriceOracle oracle) {
        return Mono.just(ApiResponse.created(gasService.registerOracle(oracle)));
    }

    @GetMapping("/oracles")
    public Mono<ApiResponse<List<GasPriceOracle>>> listOracles(
            @RequestParam(required = false) String chainType,
            @RequestParam(required = false) String networkId) {
        return Mono.just(ApiResponse.success(gasService.getActiveOracles(chainType, networkId)));
    }

    @PostMapping("/oracles/{id}/price")
    public Mono<ApiResponse<GasPriceOracle>> updateOraclePrice(
            @PathVariable String id,
            @RequestBody Map<String, Object> request) {
        BigDecimal gasPrice = request.get("gasPrice") != null ?
                new BigDecimal(request.get("gasPrice").toString()) : null;
        BigDecimal priorityFee = request.get("priorityFee") != null ?
                new BigDecimal(request.get("priorityFee").toString()) : null;
        BigDecimal baseFee = request.get("baseFee") != null ?
                new BigDecimal(request.get("baseFee").toString()) : null;

        return Mono.just(ApiResponse.success(
                gasService.updateOraclePrice(id, gasPrice, priorityFee, baseFee)));
    }

    @DeleteMapping("/oracles/{id}")
    public Mono<ApiResponse<Void>> deleteOracle(@PathVariable String id) {
        gasService.deleteOracle(id);
        return Mono.just(ApiResponse.success(null));
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getGasPriceStats(
            @RequestParam(defaultValue = "ETH") String chainType,
            @RequestParam(defaultValue = "1") String networkId) {
        return Mono.just(ApiResponse.success(gasService.getGasPriceStats(chainType, networkId)));
    }
}
