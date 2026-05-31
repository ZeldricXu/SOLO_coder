package com.scheduler.api.controller;

import com.scheduler.common.model.ApiResponse;
import com.scheduler.log.pipeline.model.LogEntry;
import com.scheduler.log.pipeline.service.LogPipelineService;
import com.scheduler.logging.service.DynamicLoggingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class LogController {

    private final LogPipelineService logPipelineService;
    private final DynamicLoggingService dynamicLoggingService;

    @PostMapping("/logs")
    public Mono<ResponseEntity<ApiResponse<LogEntry>>> ingestLog(@RequestBody LogEntry entry) {
        return logPipelineService.process(entry)
                .map(e -> ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(e)));
    }

    @PostMapping("/logs/batch")
    public Flux<ResponseEntity<ApiResponse<LogEntry>>> ingestLogs(@RequestBody List<LogEntry> entries) {
        return logPipelineService.processBatch(Flux.fromIterable(entries))
                .map(e -> ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(e)));
    }

    @GetMapping("/logs/processors")
    public Mono<ResponseEntity<ApiResponse<List<String>>>> getAvailableProcessors() {
        return Mono.fromCallable(() ->
                ResponseEntity.ok(ApiResponse.success(logPipelineService.getAvailableProcessors()))
        );
    }

    @GetMapping("/logs/metrics")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> getLogPipelineMetrics() {
        return Mono.fromCallable(() -> {
            Map<String, Object> metrics = Map.of(
                    "processedCount", logPipelineService.getProcessedCount(),
                    "errorCount", logPipelineService.getErrorCount()
            );
            return ResponseEntity.ok(ApiResponse.success(metrics));
        });
    }

    @GetMapping("/logging/levels")
    public Mono<ResponseEntity<ApiResponse<Map<String, String>>>> getAllLogLevels() {
        return Mono.fromCallable(() ->
                ResponseEntity.ok(ApiResponse.success(dynamicLoggingService.getAllLogLevels()))
        );
    }

    @GetMapping("/logging/levels/{logger}")
    public Mono<ResponseEntity<ApiResponse<Map<String, String>>>> getLogLevel(@PathVariable String logger) {
        return Mono.fromCallable(() -> {
            String level = dynamicLoggingService.getLogLevel(logger);
            return ResponseEntity.ok(ApiResponse.success(Map.of("logger", logger, "level", level)));
        });
    }

    @PutMapping("/logging/levels/{logger}")
    public Mono<ResponseEntity<ApiResponse<Map<String, String>>>> setLogLevel(
            @PathVariable String logger,
            @RequestBody Map<String, String> request) {
        return Mono.fromCallable(() -> {
            String level = request.get("level");
            String changedBy = request.getOrDefault("changedBy", "api");
            boolean success = dynamicLoggingService.setLogLevel(logger, level, changedBy);
            if (success) {
                return ResponseEntity.ok(ApiResponse.success(Map.of("logger", logger, "level", level)));
            } else {
                return ResponseEntity.badRequest().body(ApiResponse.error(400, "Failed to set log level"));
            }
        });
    }

    @DeleteMapping("/logging/levels/{logger}")
    public Mono<ResponseEntity<ApiResponse<Void>>> resetLogLevel(@PathVariable String logger) {
        return Mono.fromCallable(() -> {
            dynamicLoggingService.resetLogLevel(logger);
            return ResponseEntity.ok(ApiResponse.success(null));
        });
    }

    @PostMapping("/logging/reset")
    public Mono<ResponseEntity<ApiResponse<Void>>> resetAllLogLevels() {
        return Mono.fromCallable(() -> {
            dynamicLoggingService.resetAll();
            return ResponseEntity.ok(ApiResponse.success(null));
        });
    }

    @GetMapping("/logging/history")
    public Mono<ResponseEntity<ApiResponse<List<DynamicLoggingService.LevelChange>>>> getLogChangeHistory() {
        return Mono.fromCallable(() ->
                ResponseEntity.ok(ApiResponse.success(dynamicLoggingService.getChangeHistory()))
        );
    }
}
