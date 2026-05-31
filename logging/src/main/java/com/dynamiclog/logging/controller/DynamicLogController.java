package com.dynamiclog.logging.controller;

import com.dynamiclog.common.dto.ApiResponse;
import com.dynamiclog.common.entity.LogConfig;
import com.dynamiclog.common.enums.LogLevel;
import com.dynamiclog.logging.service.DynamicLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/logging")
@RequiredArgsConstructor
public class DynamicLogController {

    private final DynamicLogService dynamicLogService;

    @PutMapping("/levels/{loggerName}")
    public Mono<ApiResponse<LogConfig>> setLogLevel(
            @PathVariable String loggerName,
            @RequestParam LogLevel level,
            @RequestParam(defaultValue = "default") String namespace,
            @RequestParam(required = false) Long ttlSeconds) {
        return dynamicLogService.setLogLevel(loggerName, level, namespace, ttlSeconds)
                .map(ApiResponse::success);
    }

    @GetMapping("/levels/{loggerName}")
    public Mono<ApiResponse<LogConfig>> getLogLevel(
            @PathVariable String loggerName,
            @RequestParam(defaultValue = "default") String namespace) {
        return dynamicLogService.getLogConfig(loggerName, namespace)
                .map(ApiResponse::success);
    }

    @GetMapping("/levels")
    public Mono<ApiResponse<List<LogConfig>>> getAllLogLevels(
            @RequestParam(defaultValue = "default") String namespace) {
        return dynamicLogService.getAllLogConfigs(namespace)
                .collectList()
                .map(ApiResponse::success);
    }

    @DeleteMapping("/levels/{loggerName}")
    public Mono<ApiResponse<Void>> resetLogLevel(
            @PathVariable String loggerName,
            @RequestParam(defaultValue = "default") String namespace) {
        return dynamicLogService.resetLogLevel(loggerName, namespace)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @PostMapping("/levels/batch")
    public Mono<ApiResponse<List<LogConfig>>> batchSetLogLevels(
            @RequestBody List<LogConfig> configs,
            @RequestParam(defaultValue = "default") String namespace) {
        return dynamicLogService.batchSetLogLevels(configs, namespace)
                .map(ApiResponse::success);
    }

    @GetMapping(value = "/levels/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<LogConfig> streamAllLogLevels(
            @RequestParam(defaultValue = "default") String namespace,
            @RequestParam(defaultValue = "50") int pageSize) {
        return dynamicLogService.streamAllLogConfigs(namespace, pageSize);
    }

    @PostMapping(value = "/levels/stream/batch", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<LogConfig> streamBatchUpdate(
            @RequestBody Flux<LogConfig> configs,
            @RequestParam(defaultValue = "default") String namespace,
            @RequestParam(defaultValue = "10") int batchSize) {
        return dynamicLogService.batchStreamUpdate(configs, namespace, batchSize);
    }

    @PostMapping("/levels/batch/add")
    public Mono<ApiResponse<DynamicLogService.BatchResult>> addToBatch(
            @RequestBody List<LogConfig> configs,
            @RequestParam(defaultValue = "default") String namespace) {
        return dynamicLogService.addToBatch(namespace, configs)
                .map(ApiResponse::success);
    }

    @GetMapping(value = "/levels/changes", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<LogConfig> listenLogLevelChanges() {
        return dynamicLogService.listenLogLevelChanges();
    }

    @GetMapping("/processing/stats")
    public Mono<ApiResponse<Map<String, Object>>> getProcessingStats() {
        return dynamicLogService.getProcessingStats()
                .map(ApiResponse::success);
    }

    @GetMapping(value = "/levels/stream/processor", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<LogConfig> createStreamProcessor(
            @RequestParam(defaultValue = "default") String namespace,
            @RequestParam(defaultValue = "20") int batchSize,
            @RequestParam(defaultValue = "1000") long flushIntervalMs) {
        return dynamicLogService.streamSetLogLevels(namespace, batchSize, Duration.ofMillis(flushIntervalMs));
    }
}
