package com.scheduler.api.controller;

import com.scheduler.anomaly.detection.AnomalyResult;
import com.scheduler.anomaly.detection.service.AnomalyDetectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/anomaly")
@RequiredArgsConstructor
public class AnomalyDetectionController {

    private final AnomalyDetectionService anomalyDetectionService;

    @GetMapping("/detect")
    public Mono<ResponseEntity<ApiResponse<List<AnomalyResult>>>> detectAnomalies(
            @RequestParam(defaultValue = "default") String namespace,
            @RequestParam(defaultValue = "24") int historyHours) {
        return Mono.fromCallable(() -> {
            List<AnomalyResult> results = anomalyDetectionService.detectAnomalies(namespace, historyHours);
            return ResponseEntity.ok(ApiResponse.success(results));
        });
    }

    @GetMapping("/algorithms")
    public Mono<ResponseEntity<ApiResponse<List<String>>>> getAvailableAlgorithms() {
        return Mono.fromCallable(() ->
                ResponseEntity.ok(ApiResponse.success(anomalyDetectionService.getAvailableAlgorithms()))
        );
    }
}
