package com.solocoder.domain.port;

import com.solocoder.domain.model.StatsSnapshot;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface ModelEvaluationPort {

    Mono<String> submitOfflineEvaluation(String modelId, String datasetId,
                                          List<String> metrics, Map<String, Object> config);

    Flux<StatsSnapshot> getEvaluationResults(String evaluationId);

    Mono<Map<String, Object>> compareEvaluations(List<String> evaluationIds);

    Mono<Void> recordOnlinePrediction(String modelId, String predictionId,
                                       Map<String, Object> features,
                                       Map<String, Object> prediction,
                                       Object actualValue);

    Flux<StatsSnapshot> getOnlineMonitoring(String modelId, Instant startTime, Instant endTime);

    Mono<Map<String, Object>> detectDrift(String modelId, String featureName, Instant startTime, Instant endTime);

    Mono<Map<String, Object>> getModelSummary(String modelId);
}
