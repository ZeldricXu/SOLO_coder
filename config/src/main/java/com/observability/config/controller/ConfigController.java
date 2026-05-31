package com.observability.config.controller;

import com.observability.common.dto.ApiResponse;
import com.observability.common.entity.ConfigEntity;
import com.observability.common.exception.BusinessException;
import com.observability.config.service.ConfigMutationService;
import com.observability.config.service.ConfigQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/configs")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigQueryService configQueryService;
    private final ConfigMutationService configMutationService;

    @GetMapping("/{namespace}")
    public Mono<ApiResponse<Map<String, Object>>> getConfig(@PathVariable String namespace) {
        return configQueryService.loadConfig(namespace)
                .map(ApiResponse::success);
    }

    @GetMapping("/{namespace}/{key}")
    public Mono<ApiResponse<Map<String, Object>>> getConfigValue(
            @PathVariable String namespace,
            @PathVariable String key) {
        return configQueryService.getConfigValue(namespace, key)
                .map(ApiResponse::success);
    }

    @PostMapping("/{namespace}")
    public Mono<ApiResponse<ConfigEntity>> saveConfig(
            @PathVariable String namespace,
            @RequestBody Map<String, Object> parameters,
            @RequestHeader(value = "X-Config-Source", required = false) String source) {
        return configMutationService.saveConfig(namespace, parameters, source)
                .map(ApiResponse::success);
    }

    @GetMapping("/{namespace}/latest")
    public Mono<ApiResponse<ConfigEntity>> getLatestConfig(@PathVariable String namespace) {
        return configQueryService.getLatestConfig(namespace)
                .map(optional -> optional
                        .map(ApiResponse::success)
                        .orElseThrow(() -> BusinessException.notFound("Config not found for namespace: " + namespace)));
    }

    @PostMapping("/{namespace}/refresh")
    public Mono<ApiResponse<String>> refreshConfig(@PathVariable String namespace) {
        return configQueryService.refreshConfig(namespace)
                .then(Mono.just(ApiResponse.success("Config refreshed successfully")));
    }
}
