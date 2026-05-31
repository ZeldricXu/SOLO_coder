package com.dynamiclog.config.controller;

import com.dynamiclog.common.dto.ApiResponse;
import com.dynamiclog.common.entity.Config;
import com.dynamiclog.config.service.ConfigManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/configs")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigManagementService configService;

    @PostMapping
    public Mono<ApiResponse<Config>> publishConfig(
            @RequestParam String dataId,
            @RequestParam(defaultValue = "default") String namespace,
            @RequestParam(defaultValue = "DEFAULT_GROUP") String group,
            @RequestBody String content,
            @RequestParam(required = false) String description) {
        return configService.publishConfig(dataId, namespace, group, content, description)
                .map(ApiResponse::success);
    }

    @GetMapping("/{dataId}")
    public Mono<ApiResponse<Config>> getConfig(
            @PathVariable String dataId,
            @RequestParam(defaultValue = "default") String namespace) {
        return configService.getConfig(dataId, namespace)
                .map(ApiResponse::success);
    }

    @GetMapping("/{dataId}/versions/{version}")
    public Mono<ApiResponse<Config>> getConfigByVersion(
            @PathVariable String dataId,
            @PathVariable int version,
            @RequestParam(defaultValue = "default") String namespace) {
        return configService.getConfigByVersion(dataId, namespace, version)
                .map(ApiResponse::success);
    }

    @GetMapping("/{dataId}/history")
    public Mono<ApiResponse<List<Config>>> getConfigHistory(
            @PathVariable String dataId,
            @RequestParam(defaultValue = "default") String namespace) {
        return configService.getConfigHistory(dataId, namespace)
                .collectList()
                .map(ApiResponse::success);
    }

    @GetMapping
    public Mono<ApiResponse<List<Config>>> getConfigsByNamespace(
            @RequestParam(defaultValue = "default") String namespace) {
        return configService.getConfigsByNamespace(namespace)
                .collectList()
                .map(ApiResponse::success);
    }

    @PostMapping("/{dataId}/rollback")
    public Mono<ApiResponse<Config>> rollbackToVersion(
            @PathVariable String dataId,
            @RequestParam int version,
            @RequestParam(defaultValue = "default") String namespace) {
        return configService.rollbackToVersion(dataId, namespace, version)
                .map(ApiResponse::success);
    }

    @PostMapping("/{dataId}/rollback/previous")
    public Mono<ApiResponse<Config>> rollbackToPrevious(
            @PathVariable String dataId,
            @RequestParam(defaultValue = "default") String namespace) {
        return configService.rollbackToPrevious(dataId, namespace)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/{dataId}")
    public Mono<ApiResponse<Void>> deleteConfig(
            @PathVariable String dataId,
            @RequestParam(defaultValue = "default") String namespace) {
        return configService.deleteConfig(dataId, namespace)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @PostMapping("/validate")
    public Mono<ApiResponse<Map<String, Boolean>>> validateConfig(
            @RequestBody String content,
            @RequestParam(defaultValue = "json") String contentType) {
        return configService.validateConfig(content, contentType)
                .map(valid -> ApiResponse.success(Map.of("valid", valid)));
    }
}
