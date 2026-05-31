package com.metricplatform.controller;

import com.metricplatform.common.ApiResponse;
import com.metricplatform.dto.LogLevelDTO;
import com.metricplatform.entity.SysLogLevel;
import com.metricplatform.service.LogLevelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/log-levels")
@RequiredArgsConstructor
public class LogLevelController {

    private final LogLevelService logLevelService;

    @GetMapping
    public Mono<ApiResponse<List<SysLogLevel>>> getAllLogLevels() {
        return Mono.just(ApiResponse.success(logLevelService.getAllLogLevels()));
    }

    @GetMapping("/{loggerName}")
    public Mono<ApiResponse<Map<String, String>>> getLogLevel(@PathVariable String loggerName) {
        Map<String, String> result = new HashMap<>();
        result.put("loggerName", loggerName);
        result.put("currentLevel", logLevelService.getCurrentLogLevel(loggerName));
        return Mono.just(ApiResponse.success(result));
    }

    @PostMapping
    public Mono<ApiResponse<SysLogLevel>> setLogLevel(@Valid @RequestBody LogLevelDTO dto) {
        SysLogLevel config = logLevelService.setLogLevel(dto);
        return Mono.just(ApiResponse.success(config));
    }

    @PutMapping("/{loggerName}")
    public Mono<ApiResponse<SysLogLevel>> updateLogLevel(
            @PathVariable String loggerName,
            @Valid @RequestBody LogLevelDTO dto) {
        dto.setLoggerName(loggerName);
        SysLogLevel config = logLevelService.setLogLevel(dto);
        return Mono.just(ApiResponse.success(config));
    }

    @DeleteMapping("/{loggerName}")
    public Mono<ApiResponse<Void>> resetLogLevel(@PathVariable String loggerName) {
        boolean result = logLevelService.resetLogLevel(loggerName);
        if (result) {
            return Mono.just(ApiResponse.success(null));
        } else {
            return Mono.just(ApiResponse.error("重置日志级别失败"));
        }
    }

    @PostMapping("/reset-all")
    public Mono<ApiResponse<Void>> resetAllLogLevels() {
        logLevelService.resetAllLogLevels();
        return Mono.just(ApiResponse.success(null));
    }
}
