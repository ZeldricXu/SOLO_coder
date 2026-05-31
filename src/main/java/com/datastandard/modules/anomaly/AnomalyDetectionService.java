package com.datastandard.modules.anomaly;

import com.datastandard.common.dto.BatchOperationRequest;
import com.datastandard.common.dto.BatchOperationResult;
import com.datastandard.common.exception.BusinessException;
import com.datastandard.common.model.AnomalyDetectionResult;
import com.datastandard.common.util.IdGenerator;
import com.datastandard.modules.anomaly.dto.AlgorithmConfig;
import com.datastandard.modules.anomaly.dto.AnomalyDetectionRequest;
import com.datastandard.modules.anomaly.dto.AnomalyResult;
import com.datastandard.modules.anomaly.mapper.AnomalyDetectionResultMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AnomalyDetectionService {

    private final List<DetectionAlgorithm> algorithms;
    private final BaselineManager baselineManager;
    private final AnomalyResultProcessor resultProcessor;
    private final AnomalyDetectionResultMapper anomalyResultMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Validator validator;
    private final MeterRegistry meterRegistry;

    private final Counter detectionCounter;
    private final Counter anomalyFoundCounter;
    private final Counter batchDetectionCounter;
    private final Counter detectionErrorCounter;

    public AnomalyDetectionService(List<DetectionAlgorithm> algorithms,
                                   BaselineManager baselineManager,
                                   AnomalyResultProcessor resultProcessor,
                                   AnomalyDetectionResultMapper anomalyResultMapper,
                                   ApplicationEventPublisher eventPublisher,
                                   Validator validator,
                                   MeterRegistry meterRegistry) {
        this.algorithms = algorithms;
        this.baselineManager = baselineManager;
        this.resultProcessor = resultProcessor;
        this.anomalyResultMapper = anomalyResultMapper;
        this.eventPublisher = eventPublisher;
        this.validator = validator;
        this.meterRegistry = meterRegistry;

        this.detectionCounter = Counter.builder("anomaly.detection.count")
                .description("异常检测请求次数")
                .register(meterRegistry);
        this.anomalyFoundCounter = Counter.builder("anomaly.detected.count")
                .description("检测到的异常数量")
                .register(meterRegistry);
        this.batchDetectionCounter = Counter.builder("anomaly.batch.detection.count")
                .description("批量异常检测次数")
                .register(meterRegistry);
        this.detectionErrorCounter = Counter.builder("anomaly.detection.error.count")
                .description("异常检测错误次数")
                .register(meterRegistry);
    }

    public Mono<List<AnomalyResult>> detect(AnomalyDetectionRequest request) {
        return Mono.fromCallable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            detectionCounter.increment();

            try {
                var violations = validator.validate(request);
                if (!violations.isEmpty()) {
                    String errorMsg = violations.stream()
                            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                            .reduce((a, b) -> a + "; " + b)
                            .orElse("参数校验失败");
                    throw new BusinessException("PARAM_VALIDATION_ERROR", errorMsg);
                }

                AlgorithmConfig config = request.getAlgorithmConfig() != null ?
                        request.getAlgorithmConfig() : createDefaultConfig();

                List<DetectionAlgorithm> selectedAlgorithms = selectAlgorithms(config);
                if (selectedAlgorithms.isEmpty()) {
                    throw new BusinessException("NO_ALGORITHM_SELECTED", "没有可用的异常检测算法");
                }

                List<AnomalyResult> allResults = new ArrayList<>();
                for (DetectionAlgorithm algorithm : selectedAlgorithms) {
                    List<AnomalyResult> results = algorithm.detect(request, config).block();
                    if (results != null) {
                        allResults.addAll(results);
                    }
                }

                if (Boolean.TRUE.equals(config.getEnsembleMode()) && selectedAlgorithms.size() > 1) {
                    allResults = ensembleResults(allResults, selectedAlgorithms, config);
                }

                allResults = applySeverityFilter(allResults, request.getSeverityLevel());

                if (!allResults.isEmpty()) {
                    anomalyFoundCounter.increment(allResults.size());
                    saveDetectionResults(request, allResults).subscribe();
                    eventPublisher.publishEvent(new AnomalyDetectedEvent(request, allResults));
                }

                log.info("异常检测完成: detectionCode={}, metricCode={}, 异常数={}",
                        request.getDetectionCode(), request.getMetricCode(), allResults.size());

                return allResults;
            } catch (BusinessException e) {
                detectionErrorCounter.increment();
                log.error("异常检测业务异常: {}", e.getMessage(), e);
                throw e;
            } catch (Exception e) {
                detectionErrorCounter.increment();
                log.error("异常检测失败: {}", e.getMessage(), e);
                throw new BusinessException("DETECTION_FAILED", "异常检测失败: " + e.getMessage());
            } finally {
                sample.stop(meterRegistry.timer("anomaly.detection.duration"));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<BatchOperationResult<AnomalyResult>> batchDetect(BatchOperationRequest<AnomalyDetectionRequest> batchRequest) {
        return Mono.fromCallable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            batchDetectionCounter.increment();

            List<AnomalyResult> allResults = Collections.synchronizedList(new ArrayList<>());
            List<BatchOperationResult.FailedItem> failedItems = Collections.synchronizedList(new ArrayList<>());

            try {
                for (int i = 0; i < batchRequest.getItems().size(); i++) {
                    int index = i;
                    AnomalyDetectionRequest request = batchRequest.getItems().get(i);
                    try {
                        List<AnomalyResult> results = detect(request).block();
                        if (results != null) {
                            allResults.addAll(results);
                        }
                    } catch (Exception e) {
                        log.error("批量检测第{}项失败: {}", index, e.getMessage());
                        failedItems.add(BatchOperationResult.FailedItem.builder()
                                .index(index)
                                .itemId(request.getDetectionCode())
                                .errorCode("DETECTION_FAILED")
                                .errorMessage(e.getMessage())
                                .build());
                    }
                }

                log.info("批量异常检测完成: 总请求数={}, 成功数={}, 失败数={}, 总异常数={}",
                        batchRequest.getItems().size(),
                        batchRequest.getItems().size() - failedItems.size(),
                        failedItems.size(),
                        allResults.size());

                return BatchOperationResult.<AnomalyResult>builder()
                        .success(failedItems.isEmpty())
                        .totalCount(batchRequest.getItems().size())
                        .successCount(batchRequest.getItems().size() - failedItems.size())
                        .failedCount(failedItems.size())
                        .results(allResults)
                        .failedItems(failedItems)
                        .build();
            } finally {
                sample.stop(meterRegistry.timer("anomaly.batch.detection.duration"));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<AnomalyResult> detectWithDimensions(AnomalyDetectionRequest request) {
        return Mono.just(request)
                .flatMapMany(req -> {
                    if (req.getDimensions() == null || req.getDimensions().isEmpty()) {
                        return detect(req).flatMapMany(Flux::fromIterable);
                    }

                    AlgorithmConfig config = req.getAlgorithmConfig() != null ?
                            req.getAlgorithmConfig() : createDefaultConfig();

                    return Flux.fromIterable(req.getDimensions())
                            .flatMap(dimension -> {
                                AnomalyDetectionRequest dimRequest = AnomalyDetectionRequest.builder()
                                        .detectionCode(req.getDetectionCode() + "_" + dimension)
                                        .metricCode(req.getMetricCode())
                                        .entityId(req.getEntityId())
                                        .instanceId(req.getInstanceId())
                                        .dataPoints(req.getDataPoints())
                                        .algorithmConfig(config)
                                        .dimensions(List.of(dimension))
                                        .tags(req.getTags())
                                        .severityLevel(req.getSeverityLevel())
                                        .windowStart(req.getWindowStart())
                                        .windowEnd(req.getWindowEnd())
                                        .build();

                                return detect(dimRequest)
                                        .flatMapMany(Flux::fromIterable)
                                        .map(result -> {
                                            result.setAffectedDimensions(List.of(dimension));
                                            return result;
                                        });
                            });
                });
    }

    public Mono<Map<String, Object>> getDetectionSummary(String metricCode, LocalDateTime startTime, LocalDateTime endTime) {
        return Mono.fromCallable(() -> {
            List<AnomalyDetectionResult> results = anomalyResultMapper
                    .findByMetricAndTimeRange(metricCode, startTime, endTime);

            Map<String, Object> summary = new HashMap<>();
            summary.put("metricCode", metricCode);
            summary.put("startTime", startTime);
            summary.put("endTime", endTime);
            summary.put("totalAnomalies", results.size());

            Map<String, Long> severityCount = results.stream()
                    .collect(Collectors.groupingBy(AnomalyDetectionResult::getSeverity, Collectors.counting()));
            summary.put("severityDistribution", severityCount);

            Map<String, Long> typeCount = results.stream()
                    .collect(Collectors.groupingBy(AnomalyDetectionResult::getAnomalyType, Collectors.counting()));
            summary.put("typeDistribution", typeCount);

            BigDecimal avgScore = anomalyResultMapper.getAverageAnomalyScore(metricCode, startTime);
            summary.put("averageAnomalyScore", avgScore != null ? avgScore : BigDecimal.ZERO);

            return summary;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<AnomalyDetectionResult>> getActiveAnomalies(Long entityId) {
        return Mono.fromCallable(() ->
                anomalyResultMapper.findActiveByEntityId(entityId)
        ).subscribeOn(Schedulers.boundedElastic());
    }

    private List<DetectionAlgorithm> selectAlgorithms(AlgorithmConfig config) {
        if (config.getEnabledAlgorithms() != null && !config.getEnabledAlgorithms().isEmpty()) {
            return algorithms.stream()
                    .filter(algo -> config.getEnabledAlgorithms().contains(algo.getAlgorithmType()))
                    .collect(Collectors.toList());
        }

        if (config.getAlgorithmType() != null) {
            return algorithms.stream()
                    .filter(algo -> algo.getAlgorithmType().equals(config.getAlgorithmType()))
                    .collect(Collectors.toList());
        }

        return algorithms;
    }

    private List<AnomalyResult> ensembleResults(List<AnomalyResult> results,
                                                List<DetectionAlgorithm> algorithms,
                                                AlgorithmConfig config) {
        Map<String, List<AnomalyResult>> groupedByTime = new HashMap<>();
        for (AnomalyResult result : results) {
            String key = result.getDetectedAt() + "_" + result.getMetricCode();
            groupedByTime.computeIfAbsent(key, k -> new ArrayList<>()).add(result);
        }

        List<AnomalyResult> ensembled = new ArrayList<>();
        Map<String, BigDecimal> weights = config.getAlgorithmWeights() != null ?
                config.getAlgorithmWeights() : getDefaultWeights(algorithms);

        for (Map.Entry<String, List<AnomalyResult>> entry : groupedByTime.entrySet()) {
            List<AnomalyResult> group = entry.getValue();

            int voteCount = group.size();
            int threshold = Math.max(1, (int) Math.ceil(algorithms.size() * 0.5));

            if (voteCount >= threshold) {
                BigDecimal totalWeight = BigDecimal.ZERO;
                BigDecimal weightedScore = BigDecimal.ZERO;
                BigDecimal weightedConfidence = BigDecimal.ZERO;

                for (AnomalyResult r : group) {
                    BigDecimal weight = weights.getOrDefault(r.getAlgorithmType(), BigDecimal.ONE);
                    totalWeight = totalWeight.add(weight);
                    weightedScore = weightedScore.add(r.getAnomalyScore().multiply(weight));
                    weightedConfidence = weightedConfidence.add(r.getConfidence().multiply(weight));
                }

                BigDecimal finalScore = totalWeight.compareTo(BigDecimal.ZERO) > 0 ?
                        weightedScore.divide(totalWeight, 4, java.math.RoundingMode.HALF_UP) :
                        weightedScore;
                BigDecimal finalConfidence = totalWeight.compareTo(BigDecimal.ZERO) > 0 ?
                        weightedConfidence.divide(totalWeight, 4, java.math.RoundingMode.HALF_UP) :
                        weightedConfidence;

                AnomalyResult ensembleResult = group.get(0);
                ensembleResult.setResultId(IdGenerator.generateStrId());
                ensembleResult.setAnomalyScore(finalScore.min(new BigDecimal("1.0")));
                ensembleResult.setConfidence(finalConfidence.min(new BigDecimal("0.99")));
                ensembleResult.setAlgorithmType("ENSEMBLE");
                ensembleResult.setAnalysisResult(Map.of(
                        "votingAlgorithms", group.stream().map(AnomalyResult::getAlgorithmType).collect(Collectors.toList()),
                        "voteCount", voteCount,
                        "algorithmCount", algorithms.size(),
                        "weights", weights
                ));

                ensembled.add(ensembleResult);
            }
        }

        return ensembled;
    }

    private Map<String, BigDecimal> getDefaultWeights(List<DetectionAlgorithm> algorithms) {
        Map<String, BigDecimal> weights = new HashMap<>();
        BigDecimal weight = BigDecimal.ONE.divide(BigDecimal.valueOf(algorithms.size()), 4, java.math.RoundingMode.HALF_UP);
        for (DetectionAlgorithm algo : algorithms) {
            weights.put(algo.getAlgorithmType(), weight);
        }
        return weights;
    }

    private List<AnomalyResult> applySeverityFilter(List<AnomalyResult> results, String severityLevel) {
        if (severityLevel == null || severityLevel.isEmpty()) {
            return results;
        }

        Set<String> allowedSeverities = getSeverityHierarchy(severityLevel);
        return results.stream()
                .filter(r -> allowedSeverities.contains(r.getSeverity()))
                .collect(Collectors.toList());
    }

    private Set<String> getSeverityHierarchy(String severity) {
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> Set.of("CRITICAL");
            case "HIGH" -> Set.of("CRITICAL", "HIGH");
            case "MEDIUM" -> Set.of("CRITICAL", "HIGH", "MEDIUM");
            case "LOW" -> Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
            default -> Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
        };
    }

    private AlgorithmConfig createDefaultConfig() {
        return AlgorithmConfig.builder()
                .algorithmType("Z_SCORE")
                .sensitivity(new BigDecimal("3.0"))
                .minDataPoints(5)
                .maxDataPoints(10000)
                .zScoreConfig(AlgorithmConfig.ZScoreConfig.builder()
                        .zScoreThreshold(new BigDecimal("3.0"))
                        .useModifiedZScore(true)
                        .build())
                .build();
    }

    private Mono<Void> saveDetectionResults(AnomalyDetectionRequest request, List<AnomalyResult> results) {
        return Flux.fromIterable(results)
                .flatMap(result -> Mono.fromCallable(() -> {
                    AnomalyDetectionResult entity = AnomalyDetectionResult.builder()
                            .id(IdGenerator.generateId())
                            .detectionCode(request.getDetectionCode())
                            .metricCode(request.getMetricCode())
                            .entityId(request.getEntityId())
                            .instanceId(request.getInstanceId())
                            .anomalyType(result.getAnomalyType())
                            .severity(result.getSeverity())
                            .confidence(result.getConfidence())
                            .anomalyScore(result.getAnomalyScore())
                            .threshold(result.getThreshold())
                            .detectedAt(result.getDetectedAt())
                            .windowStart(request.getWindowStart())
                            .windowEnd(request.getWindowEnd())
                            .anomalyData(result.getAnomalyData())
                            .analysisResult(result.getAnalysisResult())
                            .status("ACTIVE")
                            .createdAt(LocalDateTime.now())
                            .build();
                    anomalyResultMapper.insert(entity);
                    return entity;
                }).subscribeOn(Schedulers.boundedElastic()))
                .then()
                .doOnError(e -> log.error("保存检测结果失败", e))
                .onErrorResume(e -> Mono.empty());
    }

    public List<String> getAvailableAlgorithms() {
        return algorithms.stream()
                .map(DetectionAlgorithm::getAlgorithmType)
                .collect(Collectors.toList());
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AnomalyDetectedEvent {
        private AnomalyDetectionRequest request;
        private List<AnomalyResult> results;
        private LocalDateTime timestamp;

        public AnomalyDetectedEvent(AnomalyDetectionRequest request, List<AnomalyResult> results) {
            this.request = request;
            this.results = results;
            this.timestamp = LocalDateTime.now();
        }
    }
}
