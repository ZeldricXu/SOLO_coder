package com.orchestration.monitoring.controller;

import com.orchestration.common.api.ApiConstants;
import com.orchestration.common.base.Result;
import com.orchestration.monitoring.service.MetricService;
import com.orchestration.persistence.entity.MetricAggregate;
import com.orchestration.persistence.entity.MetricData;
import com.orchestration.persistence.entity.MetricDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.API_V1_PREFIX + "/monitoring")
@RequiredArgsConstructor
public class MetricController {

    private final MetricService metricService;

    @PostMapping("/metrics")
    public Result<Long> defineMetric(@RequestBody MetricDefinition definition) {
        return Result.success(metricService.defineMetric(definition));
    }

    @GetMapping("/metrics/{id}")
    public Result<MetricDefinition> getMetricDefinition(@PathVariable Long id) {
        return Result.success(metricService.getMetricDefinition(id));
    }

    @GetMapping("/metrics")
    public Result<List<MetricDefinition>> listMetricDefinitions() {
        return Result.success(metricService.listMetricDefinitions());
    }

    @PostMapping("/metrics/collect")
    public Result<Void> collectMetric(
            @RequestParam String metricCode,
            @RequestParam Double value,
            @RequestBody(required = false) Map<String, String> labels) {
        metricService.collectMetric(metricCode, value, labels);
        return Result.success();
    }

    @PostMapping("/metrics/batch")
    public Result<Void> batchCollectMetrics(@RequestBody List<MetricData> metrics) {
        metricService.batchCollectMetrics(metrics);
        return Result.success();
    }

    @GetMapping("/metrics/{metricCode}/data")
    public Result<List<MetricData>> queryMetricData(
            @PathVariable String metricCode,
            @RequestParam Long startTime,
            @RequestParam Long endTime,
            @RequestBody(required = false) Map<String, String> labels) {
        return Result.success(metricService.queryMetricData(metricCode, startTime, endTime, labels));
    }

    @GetMapping("/metrics/{metricCode}/aggregate")
    public Result<List<MetricAggregate>> queryMetricAggregate(
            @PathVariable String metricCode,
            @RequestParam String aggregateType,
            @RequestParam String aggregatePeriod,
            @RequestParam Long startTime,
            @RequestParam Long endTime) {
        return Result.success(metricService.queryMetricAggregate(
                metricCode, aggregateType, aggregatePeriod, startTime, endTime));
    }

    @GetMapping("/dashboard")
    public Result<Map<String, Object>> getDashboardData() {
        return Result.success(metricService.getDashboardData());
    }

    @GetMapping("/metrics/top")
    public Result<List<Map<String, Object>>> getTopMetrics(@RequestParam(defaultValue = "10") int limit) {
        return Result.success(metricService.getTopMetrics(limit));
    }
}
