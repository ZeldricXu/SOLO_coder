package com.didauth.module.gas.controller;

import com.didauth.common.response.ApiResponse;
import com.didauth.core.entity.GasHistory;
import com.didauth.module.gas.dto.GasEstimateResponse;
import com.didauth.module.gas.service.GasService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/gas")
@RequiredArgsConstructor
public class GasController {

    private final GasService gasService;

    @GetMapping("/{chainType}/estimate")
    public Mono<ApiResponse<GasEstimateResponse>> estimateGas(
            @PathVariable String chainType,
            @RequestParam(defaultValue = "STANDARD") String priorityLevel) {
        return gasService.estimateGas(chainType, priorityLevel)
                .map(ApiResponse::success);
    }

    @GetMapping("/{chainType}/estimate/all")
    public Flux<GasEstimateResponse> estimateAll(@PathVariable String chainType) {
        return gasService.estimateAll(chainType);
    }

    @GetMapping("/{chainType}/history")
    public Mono<ApiResponse<List<GasHistory>>> getGasHistory(
            @PathVariable String chainType,
            @RequestParam(defaultValue = "100") Integer limit) {
        return gasService.getGasHistory(chainType, limit)
                .map(ApiResponse::success);
    }
}
