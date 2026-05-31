package com.tracetopology.web.controller;

import com.tracetopology.api.service.LogPipelineService;
import com.tracetopology.common.result.Result;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogPipelineService logPipelineService;

    @PostMapping("/ingest")
    public Mono<Result<Void>> ingestLog(@RequestBody Map<String, Object> logEntry) {
        return Mono.fromCallable(() -> {
            logPipelineService.processLog(logEntry);
            return Result.success();
        });
    }

    @PostMapping("/ingest/batch")
    public Mono<Result<Void>> ingestLogsBatch(@RequestBody List<Map<String, Object>> logs) {
        return Mono.fromCallable(() -> {
            logPipelineService.processLogs(logs);
            return Result.success();
        });
    }

    @PostMapping("/pipelines")
    public Mono<Result<String>> createPipeline(@RequestBody PipelineRequest request) {
        return Mono.fromCallable(() -> {
            String pipelineId = logPipelineService.createPipeline(
                    request.getName(),
                    request.getFilters(),
                    request.getOutputs()
            );
            return Result.success(pipelineId);
        });
    }

    @PostMapping("/filters")
    public Mono<Result<String>> addFilter(@RequestBody FilterRequest request) {
        return Mono.fromCallable(() -> {
            String filterId = logPipelineService.addFilter(
                    request.getPipelineId(),
                    request.getFilterType(),
                    request.getConfig()
            );
            return Result.success(filterId);
        });
    }

    @PostMapping("/outputs")
    public Mono<Result<String>> addOutput(@RequestBody OutputRequest request) {
        return Mono.fromCallable(() -> {
            String outputId = logPipelineService.addOutput(
                    request.getPipelineId(),
                    request.getOutputType(),
                    request.getConfig()
            );
            return Result.success(outputId);
        });
    }

    @GetMapping("/pipelines")
    public Mono<Result<List<String>>> listPipelines() {
        return Mono.fromCallable(() -> {
            List<String> pipelines = logPipelineService.listPipelines();
            return Result.success(pipelines);
        });
    }

    @GetMapping("/stats")
    public Mono<Result<Map<String, Object>>> getPipelineStats() {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = logPipelineService.getPipelineStats();
            return Result.success(stats);
        });
    }

    @PostMapping("/level")
    public Mono<Result<Void>> setLogLevel(@RequestBody LogLevelRequest request) {
        return Mono.fromCallable(() -> {
            logPipelineService.setLogLevel(request.getLoggerName(), request.getLevel());
            return Result.success();
        });
    }

    @GetMapping("/level")
    public Mono<Result<String>> getLogLevel(@RequestParam(required = false, defaultValue = "root") String loggerName) {
        return Mono.fromCallable(() -> {
            String level = logPipelineService.getLogLevel(loggerName);
            return Result.success(level);
        });
    }

    @Data
    public static class PipelineRequest {
        private String name;
        private List<String> filters;
        private List<String> outputs;
    }

    @Data
    public static class FilterRequest {
        private String pipelineId;
        private String filterType;
        private Map<String, Object> config;
    }

    @Data
    public static class OutputRequest {
        private String pipelineId;
        private String outputType;
        private Map<String, Object> config;
    }

    @Data
    public static class LogLevelRequest {
        private String loggerName;
        private String level;
    }
}
