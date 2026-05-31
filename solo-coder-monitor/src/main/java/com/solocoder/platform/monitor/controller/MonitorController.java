package com.solocoder.platform.monitor.controller;

import com.solocoder.platform.common.model.ApiResponse;
import com.solocoder.platform.monitor.model.MetricDataPoint;
import com.solocoder.platform.monitor.model.PerformanceSnapshot;
import com.solocoder.platform.monitor.service.MetricsCollector;
import com.solocoder.platform.monitor.service.MetricsQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MonitorController {

    private final MetricsCollector metricsCollector;
    private final MetricsQueryService metricsQueryService;

    @PostMapping
    public ApiResponse<Void> recordMetric(@RequestBody MetricDataPoint dataPoint) {
        metricsCollector.record(dataPoint);
        return ApiResponse.success();
    }

    @GetMapping("/{metricName}")
    public ApiResponse<List<MetricDataPoint>> queryMetrics(@PathVariable String metricName,
                                                           @RequestParam long startTimestamp,
                                                           @RequestParam long endTimestamp) {
        return ApiResponse.success(metricsQueryService.queryMetrics(metricName, startTimestamp, endTimestamp));
    }

    @GetMapping("/{metricName}/latest")
    public ApiResponse<MetricDataPoint> getLatestMetric(@PathVariable String metricName) {
        MetricDataPoint latest = metricsQueryService.getLatestMetric(metricName);
        if (latest == null) {
            return ApiResponse.error(404, "Metric not found: " + metricName);
        }
        return ApiResponse.success(latest);
    }

    @GetMapping("/performance")
    public ApiResponse<PerformanceSnapshot> getCurrentPerformance() {
        return ApiResponse.success(metricsQueryService.getCurrentPerformance());
    }

    @GetMapping("/performance/history")
    public ApiResponse<List<PerformanceSnapshot>> getPerformanceHistory(@RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(metricsQueryService.getPerformanceHistory(limit));
    }
}
