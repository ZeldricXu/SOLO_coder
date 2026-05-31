package com.solo.config.controller;

import com.solo.config.common.Result;
import com.solo.config.entity.MetricSnapshot;
import com.solo.config.module.metrics.MetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MetricsService metricsService;

    @GetMapping
    public Mono<Result<Map<String, Object>>> getMetrics() {
        return metricsService.getMetrics()
                .map(Result::success);
    }

    @GetMapping("/snapshots")
    public Flux<MetricSnapshot> listSnapshots(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return metricsService.listSnapshots(startTime, endTime);
    }

    @PostMapping("/counter/{name}")
    public Mono<Result<Void>> incrementCounter(
            @PathVariable String name,
            @RequestParam(required = false) String tags) {
        String[] tagArray = tags != null ? tags.split(",") : new String[0];
        metricsService.incrementCounter(name, tagArray);
        return Mono.just(Result.success());
    }

    @PostMapping("/timer/{name}")
    public Mono<Result<Void>> recordTimer(
            @PathVariable String name,
            @RequestParam long durationMs,
            @RequestParam(required = false) String tags) {
        String[] tagArray = tags != null ? tags.split(",") : new String[0];
        metricsService.recordTimer(name, durationMs, tagArray);
        return Mono.just(Result.success());
    }
}
