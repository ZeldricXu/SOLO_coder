package com.scheduler.api.controller;

import com.scheduler.anomaly.detection.batch.BatchDetectionRequest;
import com.scheduler.anomaly.detection.batch.BatchDetectionResult;
import com.scheduler.anomaly.detection.service.BatchAnomalyDetectionService;
import com.scheduler.common.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/anomaly/batch")
@RequiredArgsConstructor
public class BatchAnomalyController {

    private final BatchAnomalyDetectionService batchDetectionService;

    @PostMapping("/detect")
    public Mono<ApiResponse<BatchDetectionResult>> detectBatch(@RequestBody List<BatchDetectionRequest> requests) {
        return batchDetectionService.detectBatch(requests)
                .map(ApiResponse::success);
    }

    @PostMapping("/detect/single")
    public Mono<ApiResponse<List<com.scheduler.anomaly.detection.AnomalyResult>>> detectWithBatching(
            @RequestBody BatchDetectionRequest request) {
        return batchDetectionService.detectWithBatching(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/detect/namespace/{namespace}")
    public Mono<ApiResponse<BatchDetectionResult>> detectForNamespace(
            @PathVariable String namespace,
            @RequestParam(defaultValue = "24") int historyHours) {
        return batchDetectionService.detectForNamespace(namespace, historyHours)
                .map(ApiResponse::success);
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getBatchStats() {
        return Mono.fromCallable(() -> ApiResponse.success(batchDetectionService.getBatchStats()));
    }
}
