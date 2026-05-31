package com.observability.logpipe.controller;

import com.observability.common.dto.ApiResponse;
import com.observability.logpipe.model.LogEntry;
import com.observability.logpipe.model.LogPipelineConfig;
import com.observability.logpipe.service.LogPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/logs")
@RequiredArgsConstructor
public class LogPipelineController {

    private final LogPipelineService logPipelineService;

    @PostMapping("/pipeline")
    public Mono<ApiResponse<LogPipelineConfig>> createPipeline(@RequestBody LogPipelineConfig config) {
        return logPipelineService.createPipeline(config)
                .map(ApiResponse::success);
    }

    @GetMapping("/pipeline")
    public Mono<ApiResponse<List<LogPipelineConfig>>> listPipelines() {
        return logPipelineService.listPipelines()
                .map(ApiResponse::success);
    }

    @DeleteMapping("/pipeline/{pipelineId}")
    public Mono<ApiResponse<String>> deletePipeline(@PathVariable String pipelineId) {
        return logPipelineService.deletePipeline(pipelineId)
                .then(Mono.just(ApiResponse.success("Pipeline deleted successfully")));
    }

    @PostMapping("/ingest")
    public Mono<ApiResponse<String>> ingestLog(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Log-Source", defaultValue = "api") String source) {
        String rawLog = body.containsKey("raw") ?
                body.get("raw").toString() : body.toString();
        return logPipelineService.processLog(rawLog, source)
                .then(Mono.just(ApiResponse.success("Log ingested successfully")));
    }

    @GetMapping
    public Mono<ApiResponse<List<LogEntry>>> getLogs(
            @RequestParam(defaultValue = "100") int limit) {
        return logPipelineService.getLogs(limit)
                .map(ApiResponse::success);
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getStats() {
        return logPipelineService.getStats()
                .map(ApiResponse::success);
    }
}
