package com.loganalytics.api.controller;

import com.loganalytics.api.service.MetricsQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/metrics")
@CrossOrigin(origins = "*")
public class MetricsController {

    private final MetricsQueryService metricsQueryService;

    @Autowired
    public MetricsController(MetricsQueryService metricsQueryService) {
        this.metricsQueryService = metricsQueryService;
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime) {

        if (startTime == null) startTime = Instant.now().minusSeconds(3600);
        if (endTime == null) endTime = Instant.now();

        return ResponseEntity.ok(metricsQueryService.getServiceOverview(startTime, endTime));
    }

    @GetMapping("/trend")
    public ResponseEntity<Map<String, Object>> getMetricTrend(
            @RequestParam String metricName,
            @RequestParam(required = false, defaultValue = "*") String serviceName,
            @RequestParam(required = false, defaultValue = "1m") String window,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime) {

        if (startTime == null) startTime = Instant.now().minusSeconds(3600);
        if (endTime == null) endTime = Instant.now();

        return ResponseEntity.ok(metricsQueryService.getMetricTrend(
                metricName, serviceName, window, startTime, endTime));
    }

    @GetMapping("/patterns/distribution")
    public ResponseEntity<Map<String, Object>> getPatternDistribution(
            @RequestParam(required = false, defaultValue = "*") String serviceName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime) {

        if (startTime == null) startTime = Instant.now().minusSeconds(3600);
        if (endTime == null) endTime = Instant.now();

        return ResponseEntity.ok(metricsQueryService.getPatternDistribution(serviceName, startTime, endTime));
    }

    @GetMapping("/patterns/topk")
    public ResponseEntity<Map<String, Object>> getTopKPatterns(
            @RequestParam(required = false, defaultValue = "*") String serviceName,
            @RequestParam(required = false, defaultValue = "10") int k,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime) {

        if (startTime == null) startTime = Instant.now().minusSeconds(3600);
        if (endTime == null) endTime = Instant.now();

        return ResponseEntity.ok(metricsQueryService.getTopKPatterns(serviceName, k, startTime, endTime));
    }

    @GetMapping("/query")
    public ResponseEntity<Map<String, Object>> queryMetrics(
            @RequestParam String metricName,
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false) String level,
            @RequestParam(required = false, defaultValue = "1m") String window,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            @RequestParam(required = false) List<String> groupBy) {

        if (startTime == null) startTime = Instant.now().minusSeconds(3600);
        if (endTime == null) endTime = Instant.now();

        Map<String, Object> result = Map.of(
                "query", Map.of(
                        "metricName", metricName,
                        "serviceName", serviceName,
                        "level", level,
                        "window", window,
                        "startTime", startTime.toString(),
                        "endTime", endTime.toString(),
                        "groupBy", groupBy
                ),
                "results", metricsQueryService.queryMetrics(
                        metricName, serviceName, level, window, startTime, endTime, groupBy)
        );

        return ResponseEntity.ok(result);
    }
}
