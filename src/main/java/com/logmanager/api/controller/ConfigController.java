package com.logmanager.api.controller;

import com.logmanager.api.dto.ConfigDTO;
import com.logmanager.api.vo.ApiResponse;
import com.logmanager.common.enums.ConfigSource;
import com.logmanager.domain.model.ConfigDefinition;
import com.logmanager.service.ConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/configs")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;

    @PostMapping
    public Mono<ApiResponse<ConfigDefinition>> createConfig(@Valid @RequestBody ConfigDTO dto) {
        ConfigSource source = dto.getSource() != null ? ConfigSource.valueOf(dto.getSource().toUpperCase()) : ConfigSource.DATABASE;
        return configService.createConfig(dto.getNamespace(), dto.getConfigId(), dto.getParameters(), source)
                .map(ApiResponse::created);
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<ConfigDefinition>> getConfig(@PathVariable String id) {
        return configService.getConfig(id)
                .map(ApiResponse::success)
                .defaultIfEmpty(ApiResponse.error(404, "Config not found"));
    }

    @PutMapping("/{id}")
    public Mono<ApiResponse<ConfigDefinition>> updateConfig(@PathVariable String id, @RequestBody Map<String, Object> parameters) {
        return configService.updateConfig(id, parameters)
                .map(ApiResponse::success)
                .onErrorResume(e -> Mono.just(ApiResponse.error(400, e.getMessage())));
    }

    @DeleteMapping("/{id}")
    public Mono<ApiResponse<Void>> deleteConfig(@PathVariable String id) {
        return configService.deleteConfig(id)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @GetMapping("/namespace/{namespace}")
    public Mono<ApiResponse<Flux<ConfigDefinition>>> getConfigsByNamespace(@PathVariable String namespace) {
        return Mono.just(ApiResponse.success(configService.getConfigsByNamespace(namespace)));
    }

    @GetMapping("/namespace/{namespace}/key/{key}")
    public Mono<ApiResponse<ConfigDefinition>> getConfigByNamespaceAndKey(@PathVariable String namespace, @PathVariable String key) {
        return configService.getConfigByNamespaceAndKey(namespace, key)
                .map(ApiResponse::success)
                .defaultIfEmpty(ApiResponse.error(404, "Config not found"));
    }

    @PostMapping("/reload")
    public Mono<ApiResponse<Void>> reloadConfigs() {
        return configService.reloadConfigs()
                .then(Mono.just(ApiResponse.success(null)));
    }

    @GetMapping("/namespace/{namespace}/merged")
    public Mono<ApiResponse<Map<String, Object>>> getMergedConfig(@PathVariable String namespace) {
        return configService.getMergedConfig(namespace)
                .map(ApiResponse::success);
    }
}
