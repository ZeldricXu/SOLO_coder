package com.observability.anomaly.controller;

import com.observability.anomaly.algorithm.AnomalyResult;
import com.observability.anomaly.dto.AnomalyDetectionRequest;
import com.observability.anomaly.service.AnomalyDetectionService;
import com.observability.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/anomaly")
@RequiredArgsConstructor
public class AnomalyController {

    private final AnomalyDetectionService anomalyDetectionService;

    @PostMapping("/detect")
    public Mono<ApiResponse<AnomalyResult>> detect(@RequestBody AnomalyDetectionRequest request) {
        return anomalyDetectionService.detect(
                request.getMetricName(),
                request.getValue(),
                request.getAlgorithm(),
                request.getParams()
        ).map(ApiResponse::success);
    }

    @PostMapping("/detect/all")
    public Mono<ApiResponse<Map<String, AnomalyResult>>> detectAll(@RequestBody AnomalyDetectionRequest request) {
        return anomalyDetectionService.detectAll(
                request.getMetricName(),
                request.getValue(),
                request.getParams()
        ).map(ApiResponse::success);
    }

    @GetMapping("/algorithms")
    public Mono<ApiResponse<List<String>>> getAlgorithms() {
        return anomalyDetectionService.getAvailableAlgorithms()
                .map(ApiResponse::success);
    }

    @PostMapping("/history/{metricName}")
    public Mono<ApiResponse<String>> addHistoryData(
            @PathVariable String metricName,
            @RequestBody Map<String, Double> body) {
        return anomalyDetectionService.addHistoryData(metricName, body.get("value"))
                .then(Mono.just(ApiResponse.success("History data added successfully")));
    }

    @GetMapping("/history/{metricName}")
    public Mono<ApiResponse<List<Double>>> getHistory(@PathVariable String metricName) {
        return anomalyDetectionService.getHistory(metricName)
                .map(ApiResponse::success);
    }
}
