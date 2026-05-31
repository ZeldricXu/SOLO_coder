package com.solocoder.application.service;

import com.solocoder.domain.model.ApiResponse;
import com.solocoder.domain.model.StatsSnapshot;
import com.solocoder.domain.port.ModelEvaluationPort;
import com.solocoder.domain.port.StructuredLoggerPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ModelEvaluationService {

    private final ModelEvaluationPort modelEvaluationPort;
    private final StructuredLoggerPort logger;

    public Mono<ApiResponse<String>> submitOfflineEvaluation(String modelId, String datasetId,
                                                              List<String> metrics,
                                                              Map<String, Object> config) {
        Map<String, Object> context = Map.of(
                "traceId", UUID.randomUUID().toString(),
                "modelId", modelId,
                "datasetId", datasetId
        );
        logger.info("提交离线评估任务", context);

        return modelEvaluationPort.submitOfflineEvaluation(modelId, datasetId, metrics, config)
                .map(ApiResponse::success)
                .onErrorResume(e -> {
                    logger.error("离线评估提交失败", e, context);
                    return Mono.just(ApiResponse.error(500, e.getMessage()));
                });
    }

    public Mono<ApiResponse<Flux<StatsSnapshot>>> getEvaluationResults(String evaluationId) {
        return Mono.just(ApiResponse.success(
                modelEvaluationPort.getEvaluationResults(evaluationId)
        ));
    }

    public Mono<ApiResponse<Map<String, Object>>> compareEvaluations(List<String> evaluationIds) {
        return modelEvaluationPort.compareEvaluations(evaluationIds)
                .map(ApiResponse::success)
                .onErrorResume(e -> Mono.just(ApiResponse.error(500, e.getMessage())));
    }

    public Mono<ApiResponse<Void>> recordOnlinePrediction(String modelId, String predictionId,
                                                           Map<String, Object> features,
                                                           Map<String, Object> prediction,
                                                           Object actualValue) {
        return modelEvaluationPort.recordOnlinePrediction(modelId, predictionId, features,
                        prediction, actualValue)
                .then(Mono.just(ApiResponse.success(null)))
                .onErrorResume(e -> Mono.just(ApiResponse.error(500, e.getMessage())));
    }

    public Mono<ApiResponse<Flux<StatsSnapshot>>> getOnlineMonitoring(String modelId,
                                                                       Instant startTime,
                                                                       Instant endTime) {
        return Mono.just(ApiResponse.success(
                modelEvaluationPort.getOnlineMonitoring(modelId, startTime, endTime)
        ));
    }

    public Mono<ApiResponse<Map<String, Object>>> detectDrift(String modelId, String featureName,
                                                                Instant startTime, Instant endTime) {
        return modelEvaluationPort.detectDrift(modelId, featureName, startTime, endTime)
                .map(ApiResponse::success)
                .onErrorResume(e -> Mono.just(ApiResponse.error(500, e.getMessage())));
    }

    public Mono<ApiResponse<Map<String, Object>>> getModelSummary(String modelId) {
        return modelEvaluationPort.getModelSummary(modelId)
                .map(ApiResponse::success)
                .switchIfEmpty(Mono.just(ApiResponse.error(404, "模型不存在")));
    }
}
