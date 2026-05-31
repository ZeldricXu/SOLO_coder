package com.modelguard.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.ApiResponse;
import com.modelguard.common.PageResult;
import com.modelguard.dto.InferenceCallDTO;
import com.modelguard.dto.ModelProviderDTO;
import com.modelguard.dto.ModelRouteDTO;
import com.modelguard.entity.InferenceRequest;
import com.modelguard.entity.ModelProvider;
import com.modelguard.entity.ModelRoute;
import com.modelguard.service.InferenceGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/inference")
@RequiredArgsConstructor
public class InferenceGatewayController {

    private final InferenceGatewayService inferenceGatewayService;

    @PostMapping("/providers")
    public Mono<ApiResponse<ModelProvider>> registerProvider(@RequestBody ModelProviderDTO dto) {
        return inferenceGatewayService.registerProvider(dto)
                .map(ApiResponse::success);
    }

    @PutMapping("/providers/{providerId}")
    public Mono<ApiResponse<ModelProvider>> updateProvider(
            @PathVariable String providerId,
            @RequestBody ModelProviderDTO dto) {
        return inferenceGatewayService.updateProvider(providerId, dto)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/providers/{providerId}")
    public Mono<ApiResponse<Void>> deleteProvider(@PathVariable String providerId) {
        return inferenceGatewayService.deleteProvider(providerId)
                .then(Mono.just(ApiResponse.success()));
    }

    @GetMapping("/providers/{providerId}")
    public Mono<ApiResponse<ModelProvider>> getProvider(@PathVariable String providerId) {
        return inferenceGatewayService.getProvider(providerId)
                .map(ApiResponse::success);
    }

    @GetMapping("/providers")
    public Mono<ApiResponse<PageResult<ModelProvider>>> listProviders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String providerType) {
        return inferenceGatewayService.listProviders(page, size, status, providerType)
                .map(this::toPageResponse);
    }

    @GetMapping("/providers/healthy")
    public Mono<ApiResponse<List<ModelProvider>>> getHealthyProviders() {
        return inferenceGatewayService.getHealthyProviders()
                .map(ApiResponse::success);
    }

    @PostMapping("/providers/{providerId}/health-check")
    public Mono<ApiResponse<ModelProvider>> checkProviderHealth(@PathVariable String providerId) {
        return inferenceGatewayService.checkProviderHealth(providerId)
                .map(ApiResponse::success);
    }

    @PostMapping("/providers/health-check-all")
    public Mono<ApiResponse<List<ModelProvider>>> healthCheckAllProviders() {
        return inferenceGatewayService.healthCheckAllProviders()
                .collectList()
                .map(ApiResponse::success);
    }

    @PostMapping("/routes")
    public Mono<ApiResponse<ModelRoute>> createRoute(@RequestBody ModelRouteDTO dto) {
        return inferenceGatewayService.createRoute(dto)
                .map(ApiResponse::success);
    }

    @PutMapping("/routes/{routeId}")
    public Mono<ApiResponse<ModelRoute>> updateRoute(
            @PathVariable String routeId,
            @RequestBody ModelRouteDTO dto) {
        return inferenceGatewayService.updateRoute(routeId, dto)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/routes/{routeId}")
    public Mono<ApiResponse<Void>> deleteRoute(@PathVariable String routeId) {
        return inferenceGatewayService.deleteRoute(routeId)
                .then(Mono.just(ApiResponse.success()));
    }

    @GetMapping("/routes/{routeId}")
    public Mono<ApiResponse<ModelRoute>> getRoute(@PathVariable String routeId) {
        return inferenceGatewayService.getRoute(routeId)
                .map(ApiResponse::success);
    }

    @GetMapping("/routes")
    public Mono<ApiResponse<PageResult<ModelRoute>>> listRoutes(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) String status) {
        return inferenceGatewayService.listRoutes(page, size, modelName, status)
                .map(this::toPageResponse);
    }

    @GetMapping("/routes/model/{modelName}")
    public Mono<ApiResponse<ModelRoute>> getRouteByModel(@PathVariable String modelName) {
        return inferenceGatewayService.getRouteByModel(modelName)
                .map(ApiResponse::success);
    }

    @GetMapping("/routes/{routeId}/stats")
    public Mono<ApiResponse<Map<String, Object>>> getRouteStats(@PathVariable String routeId) {
        return inferenceGatewayService.getRouteStats(routeId)
                .map(ApiResponse::success);
    }

    @PostMapping("/call")
    public Mono<ApiResponse<Map<String, Object>>> callInference(@RequestBody InferenceCallDTO dto) {
        return inferenceGatewayService.callInference(dto)
                .map(ApiResponse::success);
    }

    @GetMapping("/requests/{requestId}")
    public Mono<ApiResponse<InferenceRequest>> getRequestLog(@PathVariable String requestId) {
        return inferenceGatewayService.getRequestLog(requestId)
                .map(ApiResponse::success);
    }

    @GetMapping("/requests")
    public Mono<ApiResponse<PageResult<InferenceRequest>>> listRequestLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) String providerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return inferenceGatewayService.listRequestLogs(page, size, modelName, providerId, status, startTime, endTime)
                .map(this::toPageResponse);
    }

    @PostMapping("/select-provider")
    public Mono<ApiResponse<String>> selectProvider(
            @RequestParam List<String> providerIds,
            @RequestParam(defaultValue = "round_robin") String strategy) {
        return inferenceGatewayService.selectProviderByStrategy(providerIds, strategy)
                .map(ApiResponse::success);
    }

    private <T> ApiResponse<PageResult<T>> toPageResponse(Page<T> page) {
        PageResult<T> result = new PageResult<>();
        result.setTotal(page.getTotal());
        result.setPage((int) page.getCurrent());
        result.setSize((int) page.getSize());
        result.setRecords(page.getRecords());
        return ApiResponse.success(result);
    }
}
