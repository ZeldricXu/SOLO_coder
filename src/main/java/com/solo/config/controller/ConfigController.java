package com.solo.config.controller;

import com.solo.config.common.Result;
import com.solo.config.entity.Config;
import com.solo.config.module.config.ConfigService;
import com.solo.config.module.config.ConfigSourceProperties;
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

    private final ConfigService configService;
    private final ConfigSourceProperties sourceProperties;

    @GetMapping("/{namespace}/{key}")
    public Mono<Result<Map<String, Object>>> getConfig(@PathVariable String namespace, @PathVariable String key) {
        return configService.getConfig(namespace, key)
                .map(value -> Result.success(Map.of("value", value, "namespace", namespace, "key", key)))
                .defaultIfEmpty(Result.error(404, "配置不存在"));
    }

    @PostMapping("/{namespace}/{key}")
    public Mono<Result<Config>> setConfig(
            @PathVariable String namespace,
            @PathVariable String key,
            @RequestBody Map<String, Object> body) {
        String value = body.get("value") != null ? body.get("value").toString() : "";
        String sourceType = body.get("sourceType") != null ? body.get("sourceType").toString() : "mysql";
        return configService.setConfig(namespace, key, value, sourceType)
                .map(Result::success);
    }

    @GetMapping
    public Flux<Config> listConfigs(@RequestParam(required = false) String namespace) {
        return configService.listConfigs(namespace);
    }

    @GetMapping("/{configId}")
    public Mono<Result<Config>> getConfigById(@PathVariable String configId) {
        return configService.getConfigById(configId)
                .map(Result::success)
                .defaultIfEmpty(Result.error(404, "配置不存在"));
    }

    @DeleteMapping("/{configId}")
    public Mono<Result<Void>> deleteConfig(@PathVariable String configId) {
        return configService.deleteConfig(configId)
                .then(Mono.just(Result.success()));
    }

    @PostMapping("/refresh")
    public Mono<Result<Void>> refreshConfigs() {
        configService.refreshConfigs();
        return Mono.just(Result.success());
    }

    @GetMapping("/sources/status")
    public Mono<Result<Map<String, Object>>> getSourceStatus() {
        List<Map<String, Object>> sources = sourceProperties.getSources().stream()
                .map(source -> Map.<String, Object>of(
                        "type", source.getType(),
                        "priority", source.getPriority(),
                        "enabled", source.isEnabled(),
                        "readOnly", source.isReadOnly(),
                        "writeOnly", source.isWriteOnly(),
                        "canRead", source.isEnabled() && !source.isWriteOnly(),
                        "canWrite", source.isEnabled() && !source.isReadOnly()
                ))
                .toList();

        return Mono.just(Result.success(Map.of(
                "sources", sources,
                "readSourceCount", sources.stream().filter(s -> (Boolean) s.get("canRead")).count(),
                "writeSourceCount", sources.stream().filter(s -> (Boolean) s.get("canWrite")).count()
        )));
    }

    @PostMapping("/{configId}/status")
    public Mono<Result<Config>> transitionStatus(
            @PathVariable String configId,
            @RequestBody Map<String, String> body) {
        String targetStatusStr = body.get("status");
        try {
            Config.ConfigStatus targetStatus = Config.ConfigStatus.valueOf(targetStatusStr.toUpperCase());
            return configService.transitionStatus(configId, targetStatus)
                    .map(Result::success);
        } catch (IllegalArgumentException e) {
            return Mono.just(Result.error(400, "Invalid status: " + targetStatusStr));
        }
    }
}
