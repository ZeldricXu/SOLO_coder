package com.parking.platform.monitoring.controller;

import com.parking.platform.common.dto.ApiResponse;
import com.parking.platform.monitoring.entity.MetricSnapshot;
import com.parking.platform.monitoring.service.MonitoringService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/monitoring")
public class MonitoringController {

    private final MonitoringService monitoringService;
    private final MeterRegistry meterRegistry;

    public MonitoringController(MonitoringService monitoringService, MeterRegistry meterRegistry) {
        this.monitoringService = monitoringService;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/snapshots")
    public ApiResponse<MetricSnapshot> createSnapshot(@RequestParam String name) {
        return ApiResponse.success(monitoringService.createSnapshot(name));
    }

    @GetMapping("/snapshots")
    public ApiResponse<List<MetricSnapshot>> listSnapshots() {
        return ApiResponse.success(monitoringService.getSnapshots());
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> getSummary() {
        return ApiResponse.success(monitoringService.getMetricsSummary());
    }

    @PostMapping("/counter/increment")
    public ApiResponse<Double> incrementCounter(@RequestParam String name,
                                                @RequestParam(required = false, defaultValue = "1") double amount) {
        Counter counter = monitoringService.getOrCreateCounter(name);
        counter.increment(amount);
        return ApiResponse.success(counter.count());
    }

    @PostMapping("/counter/decrement")
    public ApiResponse<Double> decrementCounter(@RequestParam String name) {
        Counter counter = monitoringService.getOrCreateCounter(name);
        counter.increment(-1);
        return ApiResponse.success(counter.count());
    }

    @PostMapping("/timer/record")
    public ApiResponse<Void> recordTimer(@RequestParam String name,
                                             @RequestParam long durationMs) {
        monitoringService.recordTimer(name, durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        return ApiResponse.success(null);
    }

    @PostMapping("/summary/record")
    public ApiResponse<Void> recordSummary(@RequestParam String name,
                                        @RequestParam double value) {
        monitoringService.recordSummary(name, value);
        return ApiResponse.success(null);
    }

    @GetMapping("/metrics")
    public ApiResponse<Map<String, Object>> getMetrics() {
        return ApiResponse.success(monitoringService.getMetricsSummary());
    }
}
