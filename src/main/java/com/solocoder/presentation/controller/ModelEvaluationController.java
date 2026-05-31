package com.solocoder.presentation.controller;

import com.solocoder.application.service.ModelEvaluationService;
import com.solocoder.domain.model.ApiResponse;
import com.solocoder.domain.model.StatsSnapshot;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/evaluation")
@RequiredArgsConstructor
public class ModelEvaluationController {

    private final ModelEvaluationService modelEvaluationService;

    @PostMapping("/offline")
    public Mono<ApiResponse<String>> submitOfflineEvaluation(
            @RequestBody @Valid Map<String, Object> request) {
        String modelId = (String) request.get("modelId");
        String datasetId = (String) request.get("datasetId");
        @SuppressWarnings("unchecked")
        List<String> metrics = (List<String>) request.getOrDefault("metrics",
                List.of("accuracy", "precision", "recall", "f1"));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) request.getOrDefault("config", Map.of());

        return modelEvaluationService.submitOfflineEvaluation(modelId, datasetId, metrics, config);
    }

    @GetMapping("/offline/{evaluationId}")
    public Mono<ApiResponse<Flux<StatsSnapshot>>> getEvaluationResults(
            @PathVariable String evaluationId) {
        return modelEvaluationService.getEvaluationResults(evaluationId);
    }

    @PostMapping("/compare")
    public Mono<ApiResponse<Map<String, Object>>> compareEvaluations(
            @RequestBody @Valid Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> evaluationIds = (List<String>) request.get("evaluationIds");
        return modelEvaluationService.compareEvaluations(evaluationIds);
    }

    @PostMapping("/online/record")
    public Mono<ApiResponse<Void>> recordOnlinePrediction(
            @RequestBody @Valid Map<String, Object> request) {
        String modelId = (String) request.get("modelId");
        String predictionId = (String) request.get("predictionId");
        @SuppressWarnings("unchecked")
        Map<String, Object> features = (Map<String, Object>) request.get("features");
        @SuppressWarnings("unchecked")
        Map<String, Object> prediction = (Map<String, Object>) request.get("prediction");
        Object actualValue = request.get("actualValue");

        return modelEvaluationService.recordOnlinePrediction(
                modelId, predictionId, features, prediction, actualValue);
    }

    @GetMapping("/online/monitoring/{modelId}")
    public Mono<ApiResponse<Flux<StatsSnapshot>>> getOnlineMonitoring(
            @PathVariable String modelId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime) {
        return modelEvaluationService.getOnlineMonitoring(modelId, startTime, endTime);
    }

    @GetMapping("/drift/{modelId}/{featureName}")
    public Mono<ApiResponse<Map<String, Object>>> detectDrift(
            @PathVariable String modelId,
            @PathVariable String featureName,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime) {
        return modelEvaluationService.detectDrift(modelId, featureName, startTime, endTime);
    }

    @GetMapping("/models/{modelId}/summary")
    public Mono<ApiResponse<Map<String, Object>>> getModelSummary(@PathVariable String modelId) {
        return modelEvaluationService.getModelSummary(modelId);
    }
}
