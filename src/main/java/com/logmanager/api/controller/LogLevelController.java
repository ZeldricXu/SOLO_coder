package com.logmanager.api.controller;

import com.logmanager.api.dto.LogLevelDTO;
import com.logmanager.api.vo.ApiResponse;
import com.logmanager.common.enums.LogLevel;
import com.logmanager.domain.model.LogLevelConfig;
import com.logmanager.service.LogLevelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/log-levels")
@RequiredArgsConstructor
public class LogLevelController {

    private final LogLevelService logLevelService;

    @PostMapping
    public Mono<ApiResponse<LogLevelConfig>> setLogLevel(@Valid @RequestBody LogLevelDTO dto) {
        LogLevel targetLevel = LogLevel.fromString(dto.getLevel());
        return logLevelService.setLogLevel(
                dto.getServiceName(),
                dto.getLoggerName(),
                targetLevel,
                dto.getTtl(),
                dto.getReason(),
                dto.getOperator()
        ).map(ApiResponse::created);
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<LogLevelConfig>> getLogLevelConfig(@PathVariable String id) {
        return logLevelService.getLogLevelConfig(id)
                .map(ApiResponse::success)
                .defaultIfEmpty(ApiResponse.error(404, "Log level config not found"));
    }

    @GetMapping("/service/{serviceName}")
    public Mono<ApiResponse<Flux<LogLevelConfig>>> getLogLevelsByService(@PathVariable String serviceName) {
        return Mono.just(ApiResponse.success(logLevelService.getLogLevelsByService(serviceName)));
    }

    @GetMapping("/active")
    public Mono<ApiResponse<Flux<LogLevelConfig>>> getAllActiveLogLevels() {
        return Mono.just(ApiResponse.success(logLevelService.getAllActiveLogLevels()));
    }

    @DeleteMapping("/{id}")
    public Mono<ApiResponse<Void>> resetLogLevel(@PathVariable String id) {
        return logLevelService.resetLogLevel(id)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @DeleteMapping("/service/{serviceName}")
    public Mono<ApiResponse<Void>> resetAllLogLevels(@PathVariable String serviceName) {
        return logLevelService.resetAllLogLevels(serviceName)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @GetMapping("/service/{serviceName}/current")
    public Mono<ApiResponse<Map<String, LogLevel>>> getCurrentLogLevels(@PathVariable String serviceName) {
        return logLevelService.getCurrentLogLevels(serviceName)
                .map(ApiResponse::success);
    }

    @PostMapping("/clean-expired")
    public Mono<ApiResponse<Void>> cleanExpiredConfigs() {
        return logLevelService.cleanExpiredConfigs()
                .then(Mono.just(ApiResponse.success(null)));
    }
}
