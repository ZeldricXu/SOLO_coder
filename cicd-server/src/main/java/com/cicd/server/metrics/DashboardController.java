package com.cicd.server.metrics;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final MetricsService metricsService;

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getDashboardOverview(
            @RequestParam(required = false) Long projectId) {
        if (projectId == null) {
            projectId = 1L;
        }
        return ResponseEntity.ok(metricsService.getDashboardOverview(projectId));
    }

    @GetMapping("/pipeline-stats")
    public ResponseEntity<Map<String, Object>> getPipelineStats(
            @RequestParam Long projectId,
            @RequestParam(defaultValue = "24h") String range) {
        return ResponseEntity.ok(metricsService.getPipelineStats(projectId, range));
    }

    @GetMapping("/dora-metrics")
    public ResponseEntity<Map<String, Object>> getDoraMetrics(
            @RequestParam Long projectId,
            @RequestParam(defaultValue = "30d") String range) {
        return ResponseEntity.ok(metricsService.getDoraMetrics(projectId, range));
    }

    @GetMapping("/environment-versions")
    public ResponseEntity<List<Map<String, Object>>> getEnvironmentVersions(
            @RequestParam Long projectId) {
        return ResponseEntity.ok(metricsService.getEnvironmentVersions(projectId));
    }
}
