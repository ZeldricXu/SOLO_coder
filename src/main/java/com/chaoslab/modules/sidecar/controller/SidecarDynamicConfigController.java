package com.chaoslab.modules.sidecar.controller;

import com.chaoslab.common.ApiResponse;
import com.chaoslab.entity.ConfigTemplate;
import com.chaoslab.entity.DynamicConfig;
import com.chaoslab.modules.sidecar.dto.ConfigApplyRequest;
import com.chaoslab.modules.sidecar.dto.ConfigTemplateCreateRequest;
import com.chaoslab.modules.sidecar.dto.DynamicConfigCreateRequest;
import com.chaoslab.modules.sidecar.dto.DynamicConfigUpdateRequest;
import com.chaoslab.modules.sidecar.service.SidecarDynamicConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sidecar/config")
@RequiredArgsConstructor
public class SidecarDynamicConfigController {

    private final SidecarDynamicConfigService dynamicConfigService;

    @PostMapping("/dynamic")
    public Mono<ApiResponse<DynamicConfig>> createDynamicConfig(@RequestBody DynamicConfigCreateRequest request) {
        return dynamicConfigService.createDynamicConfig(request)
                .map(ApiResponse::success);
    }

    @PutMapping("/dynamic")
    public Mono<ApiResponse<DynamicConfig>> updateDynamicConfig(@RequestBody DynamicConfigUpdateRequest request) {
        return dynamicConfigService.updateDynamicConfig(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/dynamic/{configKey}")
    public Mono<ApiResponse<DynamicConfig>> getDynamicConfig(@PathVariable String configKey) {
        return dynamicConfigService.getDynamicConfig(configKey)
                .map(ApiResponse::success);
    }

    @GetMapping("/dynamic")
    public Mono<ApiResponse<List<DynamicConfig>>> listDynamicConfigs(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String configType) {
        return dynamicConfigService.listDynamicConfigs(scope, configType)
                .map(ApiResponse::success);
    }

    @PostMapping("/dynamic/rollback/{logId}")
    public Mono<ApiResponse<Void>> rollbackConfig(@PathVariable String logId) {
        return dynamicConfigService.rollbackConfig(logId)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @PostMapping("/template")
    public Mono<ApiResponse<ConfigTemplate>> createConfigTemplate(@RequestBody ConfigTemplateCreateRequest request) {
        return dynamicConfigService.createConfigTemplate(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/template/scenario/{scenario}")
    public Mono<ApiResponse<List<ConfigTemplate>>> getTemplatesByScenario(@PathVariable String scenario) {
        return dynamicConfigService.getTemplatesByScenario(scenario)
                .map(ApiResponse::success);
    }

    @PostMapping("/template/apply")
    public Mono<ApiResponse<com.chaoslab.entity.SidecarConfig>> applyTemplateToInstance(@RequestBody ConfigApplyRequest request) {
        return dynamicConfigService.applyTemplateToInstance(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/template/apply/namespace/{namespace}/{templateId}")
    public Flux<ApiResponse<com.chaoslab.entity.SidecarConfig>> applyTemplateToNamespace(
            @PathVariable String namespace,
            @PathVariable String templateId,
            @RequestParam String appliedBy,
            @RequestParam(required = false) String reason) {
        return dynamicConfigService.applyTemplateToNamespace(namespace, templateId, appliedBy, reason)
                .map(ApiResponse::success);
    }

    @GetMapping("/effective/{instanceId}")
    public Mono<ApiResponse<Map<String, Object>>> getEffectiveConfig(@PathVariable String instanceId) {
        return dynamicConfigService.getEffectiveConfig(instanceId)
                .map(ApiResponse::success);
    }

    @PostMapping("/cache/refresh")
    public Mono<ApiResponse<Void>> refreshConfigCache() {
        return dynamicConfigService.refreshConfigCache()
                .then(Mono.just(ApiResponse.success(null)));
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getConfigStats() {
        return dynamicConfigService.getConfigStats()
                .map(ApiResponse::success);
    }
}
