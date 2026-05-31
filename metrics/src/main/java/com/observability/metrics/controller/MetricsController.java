package com.observability.metrics.controller;

import com.observability.common.dto.ApiResponse;
import com.observability.metrics.model.MetricPoint;
import com.observability.metrics.service.MetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MetricsService metricsService;

    @PostMapping("/ingest")
    public Mono<ApiResponse<String>> ingest(@RequestBody MetricPoint point) {
        return metricsService.ingestMetric(point)
                .then(Mono.just(ApiResponse.success("Metric ingested successfully")));
    }

    @GetMapping("/{metricName}/aggregate")
    public Mono<ApiResponse<Map<String, Object>>> aggregate(
            @PathVariable String metricName,
            @RequestParam(required = false) Map<String, String> labels) {
        return metricsService.aggregate(metricName, labels)
                .map(ApiResponse::success);
    }

    @GetMapping("/{metricName}")
    public Mono<ApiResponse<List<MetricPoint>>> query(
            @PathVariable String metricName,
            @RequestParam(required = false) Map<String, String> labels) {
        return metricsService.queryMetric(metricName, labels)
                .map(ApiResponse::success);
    }
}
