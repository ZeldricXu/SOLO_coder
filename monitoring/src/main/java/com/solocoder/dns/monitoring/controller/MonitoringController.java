package com.solocoder.dns.monitoring.controller;

import com.solocoder.dns.common.model.ApiResponse;
import com.solocoder.dns.common.model.PageResult;
import com.solocoder.dns.common.entity.StatsSnapshot;
import com.solocoder.dns.monitoring.service.MetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/monitoring")
@RequiredArgsConstructor
public class MonitoringController {
    private final MetricsService metricsService;

    @GetMapping("/metrics")
    public ApiResponse<Map<String, Object>> getMetrics() {
        return ApiResponse.success(metricsService.getCurrentMetrics());
    }

    @PostMapping("/snapshots")
    public ApiResponse<StatsSnapshot> createSnapshot(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) request.get("metrics");
        @SuppressWarnings("unchecked")
        Map<String, String> dimensions = (Map<String, String>) request.get("dimensions");
        return ApiResponse.success(201, metricsService.createSnapshot(metrics, dimensions));
    }

    @GetMapping("/snapshots")
    public ApiResponse<PageResult<StatsSnapshot>> listSnapshots(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(metricsService.listSnapshots(page, size));
    }

    @GetMapping("/snapshots/{id}")
    public ApiResponse<StatsSnapshot> getSnapshot(@PathVariable String id) {
        return ApiResponse.success(metricsService.getSnapshot(id));
    }

    @PostMapping("/counter/{name}")
    public ApiResponse<Void> incrementCounter(@PathVariable String name,
                                              @RequestParam(defaultValue = "") String tags) {
        String[] tagArray = tags.isEmpty() ? new String[0] : tags.split(",");
        metricsService.incrementCounter(name, tagArray);
        return ApiResponse.success(null);
    }
}
