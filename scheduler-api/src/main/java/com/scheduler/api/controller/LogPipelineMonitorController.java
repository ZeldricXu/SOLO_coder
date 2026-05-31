package com.scheduler.api.controller;

import com.scheduler.common.model.ApiResponse;
import com.scheduler.log.pipeline.monitor.PipelineMetrics;
import com.scheduler.log.pipeline.monitor.PipelineStatusMonitor;
import com.scheduler.log.pipeline.monitor.StageLatencyTracker;
import com.scheduler.log.pipeline.service.LogPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/log-pipeline/monitor")
@RequiredArgsConstructor
public class LogPipelineMonitorController {

    private final LogPipelineService pipelineService;
    private final PipelineMetrics pipelineMetrics;
    private final PipelineStatusMonitor statusMonitor;
    private final StageLatencyTracker latencyTracker;

    @GetMapping("/status")
    public Mono<ApiResponse<Map<String, Object>>> getStatus() {
        return pipelineService.getCurrentStatus()
                .map(ApiResponse::success);
    }

    @GetMapping("/metrics")
    public Mono<ApiResponse<Map<String, Object>>> getMetrics() {
        return Mono.fromCallable(() -> ApiResponse.success(pipelineMetrics.getStats()));
    }

    @GetMapping("/latency")
    public Mono<ApiResponse<Map<String, Object>>> getLatencyStats() {
        return Mono.fromCallable(() -> ApiResponse.success(latencyTracker.getStageLatencyStats()));
    }

    @PostMapping("/latency/reset")
    public Mono<ApiResponse<String>> resetLatencyStats() {
        return Mono.fromCallable(() -> {
            latencyTracker.resetStats();
            return ApiResponse.success("Latency statistics reset");
        });
    }

    @GetMapping("/health")
    public Mono<ApiResponse<Boolean>> healthCheck() {
        return Mono.fromCallable(() -> ApiResponse.success(statusMonitor.isHealthy()));
    }

    @GetMapping("/throughput")
    public Mono<ApiResponse<Map<String, Object>>> getThroughput() {
        return Mono.fromCallable(() -> ApiResponse.success(Map.of(
                "throughputPerSecond", String.format("%.2f", statusMonitor.getCurrentThroughput()),
                "errorRate", String.format("%.4f", statusMonitor.getCurrentErrorRate()),
                "totalProcessed", pipelineMetrics.getTotalProcessed().get(),
                "totalErrors", pipelineMetrics.getTotalErrors().get()
        )));
    }
}
