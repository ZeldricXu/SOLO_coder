package com.solocoder.infrastructure.adapter.evaluation;

import com.solocoder.domain.model.StatsSnapshot;
import com.solocoder.domain.port.ModelEvaluationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class InMemoryModelEvaluationAdapter implements ModelEvaluationPort {

    private final Map<String, List<StatsSnapshot>> evaluationResults = new ConcurrentHashMap<>();
    private final Map<String, List<StatsSnapshot>> onlineMetrics = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> predictionRecords = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> modelRegistry = new ConcurrentHashMap<>();

    @Override
    public Mono<String> submitOfflineEvaluation(String modelId, String datasetId,
                                                 List<String> metrics,
                                                 Map<String, Object> config) {
        return Mono.fromCallable(() -> {
            String evaluationId = "eval_" + UUID.randomUUID().toString().replace("-", "");

            List<StatsSnapshot> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Map<String, Double> metricValues = new HashMap<>();
                for (String metric : metrics) {
                    metricValues.put(metric, 0.5 + Math.random() * 0.5);
                }
                results.add(StatsSnapshot.builder()
                        .snapshotId("snap_" + i)
                        .timestamp(Instant.now())
                        .metrics(metricValues)
                        .dimensions(Map.of(
                                "modelId", modelId,
                                "datasetId", datasetId,
                                "fold", String.valueOf(i)
                        ))
                        .build());
            }

            evaluationResults.put(evaluationId, results);
            return evaluationId;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<StatsSnapshot> getEvaluationResults(String evaluationId) {
        return Flux.fromIterable(evaluationResults.getOrDefault(evaluationId, Collections.emptyList()));
    }

    @Override
    public Mono<Map<String, Object>> compareEvaluations(List<String> evaluationIds) {
        return Mono.fromCallable(() -> {
            Map<String, Object> comparison = new HashMap<>();
            Map<String, Map<String, Double>> metricAverages = new HashMap<>();

            for (String evalId : evaluationIds) {
                List<StatsSnapshot> results = evaluationResults.getOrDefault(evalId, Collections.emptyList());
                if (!results.isEmpty()) {
                    Map<String, Double> avgMetrics = new HashMap<>();
                    for (StatsSnapshot snapshot : results) {
                        snapshot.getMetrics().forEach((metric, value) ->
                                avgMetrics.merge(metric, value, Double::sum));
                    }
                    avgMetrics.replaceAll((k, v) -> v / results.size());
                    metricAverages.put(evalId, avgMetrics);
                }
            }

            comparison.put("metricAverages", metricAverages);
            comparison.put("evaluationCount", evaluationIds.size());
            comparison.put("bestEvaluation", findBestEvaluation(metricAverages));

            return comparison;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> recordOnlinePrediction(String modelId, String predictionId,
                                              Map<String, Object> features,
                                              Map<String, Object> prediction,
                                              Object actualValue) {
        return Mono.fromRunnable(() -> {
            Map<String, Object> record = new HashMap<>();
            record.put("modelId", modelId);
            record.put("predictionId", predictionId);
            record.put("features", features);
            record.put("prediction", prediction);
            record.put("actualValue", actualValue);
            record.put("timestamp", Instant.now());
            predictionRecords.put(predictionId, record);

            List<StatsSnapshot> modelMetrics = onlineMetrics.computeIfAbsent(modelId, k -> new ArrayList<>());
            Map<String, Double> metrics = new HashMap<>();
            metrics.put("predictionCount", 1.0);
            if (actualValue != null && prediction != null) {
                Object predictedValue = prediction.get("value");
                if (Objects.equals(predictedValue, actualValue)) {
                    metrics.put("accuracy", 1.0);
                } else {
                    metrics.put("accuracy", 0.0);
                }
            }

            modelMetrics.add(StatsSnapshot.builder()
                    .snapshotId("snap_" + System.currentTimeMillis())
                    .timestamp(Instant.now())
                    .metrics(metrics)
                    .dimensions(Map.of("modelId", modelId, "predictionId", predictionId))
                    .build());
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Flux<StatsSnapshot> getOnlineMonitoring(String modelId, Instant startTime, Instant endTime) {
        return Flux.fromIterable(onlineMetrics.getOrDefault(modelId, Collections.emptyList()))
                .filter(snapshot -> !snapshot.getTimestamp().isBefore(startTime)
                        && !snapshot.getTimestamp().isAfter(endTime));
    }

    @Override
    public Mono<Map<String, Object>> detectDrift(String modelId, String featureName,
                                                  Instant startTime, Instant endTime) {
        return Mono.fromCallable(() -> {
            Map<String, Object> driftResult = new HashMap<>();

            double baselineMean = 0.5;
            double baselineStd = 0.1;

            double currentMean = 0.7;
            double currentStd = 0.2;

            double ksStatistic = Math.abs(currentMean - baselineMean) /
                    Math.sqrt(baselineStd * baselineStd + currentStd * currentStd);

            boolean isDrifted = ksStatistic > 0.3;

            driftResult.put("featureName", featureName);
            driftResult.put("modelId", modelId);
            driftResult.put("baselineMean", baselineMean);
            driftResult.put("baselineStd", baselineStd);
            driftResult.put("currentMean", currentMean);
            driftResult.put("currentStd", currentStd);
            driftResult.put("ksStatistic", ksStatistic);
            driftResult.put("isDrifted", isDrifted);
            driftResult.put("threshold", 0.3);
            driftResult.put("timeRange", Map.of("start", startTime, "end", endTime));

            return driftResult;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<String, Object>> getModelSummary(String modelId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> summary = modelRegistry.computeIfAbsent(modelId, k -> {
                Map<String, Object> modelSummary = new HashMap<>();
                modelSummary.put("modelId", modelId);
                modelSummary.put("name", "Model " + modelId);
                modelSummary.put("version", "1.0.0");
                modelSummary.put("status", "active");
                modelSummary.put("totalPredictions", (long) (Math.random() * 100000));
                modelSummary.put("accuracy", 0.85 + Math.random() * 0.1);
                modelSummary.put("precision", 0.8 + Math.random() * 0.15);
                modelSummary.put("recall", 0.75 + Math.random() * 0.2);
                modelSummary.put("f1Score", 0.8 + Math.random() * 0.15);
                modelSummary.put("lastUpdated", Instant.now());
                return modelSummary;
            });
            return summary;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String findBestEvaluation(Map<String, Map<String, Double>> metricAverages) {
        String best = null;
        double bestScore = -1;

        for (Map.Entry<String, Map<String, Double>> entry : metricAverages.entrySet()) {
            double avg = entry.getValue().values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            if (avg > bestScore) {
                bestScore = avg;
                best = entry.getKey();
            }
        }
        return best;
    }
}
