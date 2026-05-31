package com.metricplatform.controller;

import com.metricplatform.common.ApiResponse;
import com.metricplatform.dto.ConfigDTO;
import com.metricplatform.entity.SysConfig;
import com.metricplatform.service.ConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/configs")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;

    @GetMapping("/namespaces")
    public Mono<ApiResponse<Set<String>>> getNamespaces() {
        return Mono.just(ApiResponse.success(configService.getNamespaces()));
    }

    @GetMapping
    public Mono<ApiResponse<List<SysConfig>>> getAllConfigs(
            @RequestParam(required = false) String namespace) {
        return Mono.just(ApiResponse.success(configService.getAllConfigs(namespace)));
    }

    @GetMapping("/{namespace}/active")
    public Mono<ApiResponse<SysConfig>> getActiveConfig(@PathVariable String namespace) {
        SysConfig config = configService.getActiveConfig(namespace);
        if (config != null) {
            return Mono.just(ApiResponse.success(config));
        } else {
            return Mono.just(ApiResponse.notFound("该命名空间无生效配置: " + namespace));
        }
    }

    @GetMapping("/{namespace}/{version}")
    public Mono<ApiResponse<SysConfig>> getConfigByVersion(
            @PathVariable String namespace,
            @PathVariable int version) {
        SysConfig config = configService.getConfigByVersion(namespace, version);
        if (config != null) {
            return Mono.just(ApiResponse.success(config));
        } else {
            return Mono.just(ApiResponse.notFound("配置不存在"));
        }
    }

    @GetMapping("/{namespace}/parameters/{key}")
    public Mono<ApiResponse<Map<String, Object>>> getParameter(
            @PathVariable String namespace,
            @PathVariable String key,
            @RequestParam(required = false) String defaultValue) {
        Object value = configService.getParameter(namespace, key);
        Map<String, Object> result = new HashMap<>();
        result.put("namespace", namespace);
        result.put("key", key);
        result.put("value", value != null ? value : defaultValue);
        result.put("found", value != null);
        return Mono.just(ApiResponse.success(result));
    }

    @PostMapping
    public Mono<ApiResponse<SysConfig>> createConfig(@Valid @RequestBody ConfigDTO dto) {
        try {
            SysConfig config = configService.createConfig(dto);
            return Mono.just(ApiResponse.created(config));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.validationError(e.getMessage()));
        }
    }

    @PutMapping("/{configId}")
    public Mono<ApiResponse<SysConfig>> updateConfig(
            @PathVariable String configId,
            @Valid @RequestBody ConfigDTO dto) {
        try {
            SysConfig config = configService.updateConfig(configId, dto);
            return Mono.just(ApiResponse.success(config));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.validationError(e.getMessage()));
        }
    }

    @PostMapping("/{configId}/apply")
    public Mono<ApiResponse<SysConfig>> applyConfig(@PathVariable String configId) {
        try {
            SysConfig config = configService.applyConfig(configId);
            return Mono.just(ApiResponse.success(config));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.notFound(e.getMessage()));
        }
    }

    @DeleteMapping("/{configId}")
    public Mono<ApiResponse<Void>> deleteConfig(@PathVariable String configId) {
        boolean result = configService.deleteConfig(configId);
        if (result) {
            return Mono.just(ApiResponse.success(null));
        } else {
            return Mono.just(ApiResponse.notFound("配置不存在"));
        }
    }
}
