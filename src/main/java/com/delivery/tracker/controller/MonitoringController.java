package com.delivery.tracker.controller;

import com.delivery.tracker.common.Result;
import com.delivery.tracker.entity.AlertRule;
import com.delivery.tracker.entity.MetricSnapshot;
import com.delivery.tracker.service.MonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final MonitoringService monitoringService;

    @PostMapping("/metrics")
    public Mono<Result<MetricSnapshot>> recordMetric(@RequestBody Map<String, Object> request) {
        String metricName = (String) request.get("metricName");
        BigDecimal value = new BigDecimal(request.get("value").toString());
        @SuppressWarnings("unchecked")
        Map<String, String> dimensions = (Map<String, String>) request.get("dimensions");

        return monitoringService.recordMetric(metricName, value, dimensions)
                .map(Result::success);
    }

    @GetMapping("/metrics")
    public Mono<Result<Map<String, BigDecimal>>> getCurrentMetrics() {
        return monitoringService.getCurrentMetrics()
                .map(Result::success);
    }

    @PostMapping("/alerts/rules")
    public Mono<Result<AlertRule>> createAlertRule(@RequestBody AlertRule rule) {
        return monitoringService.createAlertRule(rule)
                .map(Result::success);
    }

    @GetMapping("/alerts/rules")
    public Mono<Result<List<AlertRule>>> getAllAlertRules() {
        return monitoringService.getAllAlertRules()
                .collectList()
                .map(Result::success);
    }
}
