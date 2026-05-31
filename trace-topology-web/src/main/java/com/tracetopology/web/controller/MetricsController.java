package com.tracetopology.web.controller;

import com.tracetopology.api.service.AnomalyDetectionService;
import com.tracetopology.api.service.MetricsService;
import com.tracetopology.common.result.Result;
import com.tracetopology.domain.entity.Snapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MetricsService metricsService;
    private final AnomalyDetectionService anomalyDetectionService;

    @PostMapping("/ingest")
    public Mono<Result<Void>> ingestMetric(@RequestBody Map<String, Object> metricData) {
        return Mono.fromCallable(() -> {
            String metricName = (String) metricData.get("name");
            double value = ((Number) metricData.get("value")).doubleValue();
            @SuppressWarnings("unchecked")
            Map<String, String> dimensions = (Map<String, String>) metricData.getOrDefault("dimensions", Map.of());
            long timestamp = metricData.containsKey("timestamp")
                    ? ((Number) metricData.get("timestamp")).longValue()
                    : System.currentTimeMillis();

            metricsService.ingestMetric(metricName, value, dimensions, timestamp);
            return Result.success();
        });
    }

    @PostMapping("/ingest/batch")
    public Mono<Result<Void>> ingestMetricsBatch(@RequestBody List<Map<String, Object>> metricsBatch) {
        return Mono.fromCallable(() -> {
            metricsService.ingestMetrics(metricsBatch);
            return Result.success();
        });
    }

    @PostMapping("/snapshot")
    public Mono<Result<Snapshot>> createSnapshot(@RequestBody Map<String, String> dimensions) {
        return Mono.fromCallable(() -> {
            Snapshot snapshot = metricsService.createSnapshot(dimensions);
            return Result.success(snapshot);
        });
    }

    @GetMapping("/query")
    public Mono<Result<Map<String, Object>>> queryMetrics(
            @RequestParam String metricName,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            @RequestParam(defaultValue = "avg") String aggregator,
            @RequestParam(required = false) Map<String, String> dimensions) {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = metricsService.getAggregatedMetrics(
                    metricName, startTime, endTime,
                    dimensions != null ? dimensions : Map.of(),
                    aggregator);
            return Result.success(result);
        });
    }

    @GetMapping("/anomaly/detect")
    public Mono<Result<Map<String, Object>>> detectAnomaly(
            @RequestParam String metricName,
            @RequestParam double value,
            @RequestParam(defaultValue = "zscore") String algorithm,
            @RequestParam(required = false) Map<String, String> dimensions) {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = anomalyDetectionService.detectAnomaly(
                    metricName, value,
                    dimensions != null ? dimensions : Map.of(),
                    algorithm);
            return Result.success(result);
        });
    }

    @GetMapping("/anomaly/algorithms")
    public Mono<Result<List<String>>> getSupportedAlgorithms() {
        return Mono.fromCallable(() -> {
            List<String> algorithms = anomalyDetectionService.getSupportedAlgorithms();
            return Result.success(algorithms);
        });
    }

    @GetMapping("/value")
    public Mono<Result<Double>> getMetricValue(
            @RequestParam String metricName,
            @RequestParam(required = false) Map<String, String> dimensions) {
        return Mono.fromCallable(() -> {
            double value = metricsService.getMetricValue(
                    metricName,
                    dimensions != null ? dimensions : Map.of());
            return Result.success(value);
        });
    }
}
