package com.modelguard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelguard.dto.DriftDetectionDTO;
import com.modelguard.dto.EvaluationCreateDTO;
import com.modelguard.dto.MonitoringRecordDTO;
import com.modelguard.entity.DriftDetection;
import com.modelguard.entity.EvaluationMetric;
import com.modelguard.entity.OnlineMonitoring;
import com.modelguard.exception.BusinessException;
import com.modelguard.exception.ResourceNotFoundException;
import com.modelguard.mapper.DriftDetectionMapper;
import com.modelguard.mapper.EvaluationMetricMapper;
import com.modelguard.mapper.OnlineMonitoringMapper;
import com.modelguard.service.ModelEvaluationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelEvaluationServiceImpl implements ModelEvaluationService {

    private final EvaluationMetricMapper evaluationMetricMapper;
    private final OnlineMonitoringMapper onlineMonitoringMapper;
    private final DriftDetectionMapper driftDetectionMapper;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private static final String EVAL_CACHE_PREFIX = "eval:";
    private static final String MONITOR_CACHE_PREFIX = "monitor:";
    private static final String DRIFT_CACHE_PREFIX = "drift:";

    private final Counter evaluationCreatedCounter;
    private final Counter driftDetectedCounter;
    private final Counter driftAlertCounter;
    private final Counter monitoringRecordedCounter;

    {
        evaluationCreatedCounter = Counter.builder("evaluation.created")
                .description("Evaluations created")
                .register(io.micrometer.core.instrument.Metrics.globalRegistry);
        driftDetectedCounter = Counter.builder("drift.detected")
                .description("Drift detections")
                .register(io.micrometer.core.instrument.Metrics.globalRegistry);
        driftAlertCounter = Counter.builder("drift.alerts")
                .description("Drift alerts")
                .register(io.micrometer.core.instrument.Metrics.globalRegistry);
        monitoringRecordedCounter = Counter.builder("monitoring.recorded")
                .description("Monitoring records")
                .register(io.micrometer.core.instrument.Metrics.globalRegistry);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<EvaluationMetric> createEvaluation(EvaluationCreateDTO dto) {
        return Mono.fromCallable(() -> {
            String evaluationId = dto.getEvaluationId() != null ? dto.getEvaluationId() : "eval_" + IdUtil.simpleUUID();

            LambdaQueryWrapper<EvaluationMetric> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(EvaluationMetric::getEvaluationId, evaluationId);
            if (evaluationMetricMapper.selectCount(wrapper) > 0) {
                throw new BusinessException("Evaluation ID already exists: " + evaluationId);
            }

            EvaluationMetric eval = new EvaluationMetric();
            eval.setEvaluationId(evaluationId);
            eval.setModelId(dto.getModelId());
            eval.setVersion(dto.getVersion());
            eval.setEvaluationType(dto.getEvaluationType());
            eval.setDatasetName(dto.getDatasetName());
            eval.setDatasetVersion(dto.getDatasetVersion());
            eval.setMetrics(dto.getMetrics());
            eval.setMetricDetails(dto.getMetricDetails());
            eval.setBaselineModelId(dto.getBaselineModelId());
            eval.setBaselineVersion(dto.getBaselineVersion());
            eval.setEvaluatedBy(dto.getEvaluatedBy());
            eval.setNotes(dto.getNotes());
            eval.setMetadata(dto.getMetadata());
            eval.setStartTime(LocalDateTime.now());
            eval.setEndTime(LocalDateTime.now());
            eval.setStatus("completed");

            if (dto.getBaselineModelId() != null) {
                eval.setComparisonResults(generateComparison(dto));
            }

            evaluationMetricMapper.insert(eval);
            evaluationCreatedCounter.increment();

            String cacheKey = EVAL_CACHE_PREFIX + evaluationId;
            redisTemplate.opsForValue().set(cacheKey, toJson(eval), Duration.ofMinutes(30)).subscribe();

            log.info("Evaluation created: {} for model {}@{}", evaluationId, dto.getModelId(), dto.getVersion());
            return eval;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> generateComparison(EvaluationCreateDTO dto) {
        Map<String, Object> comparison = new HashMap<>();
        if (dto.getMetrics() == null) {
            return comparison;
        }

        LambdaQueryWrapper<EvaluationMetric> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EvaluationMetric::getModelId, dto.getBaselineModelId())
                .eq(EvaluationMetric::getVersion, dto.getBaselineVersion())
                .eq(EvaluationMetric::getEvaluationType, dto.getEvaluationType())
                .orderByDesc(EvaluationMetric::getCreatedAt)
                .last("LIMIT 1");
        EvaluationMetric baseline = evaluationMetricMapper.selectOne(wrapper);

        if (baseline != null && baseline.getMetrics() != null) {
            Map<String, Object> diffs = new HashMap<>();
            for (Map.Entry<String, Object> entry : dto.getMetrics().entrySet()) {
                String key = entry.getKey();
                Object currentValue = entry.getValue();
                Object baselineValue = baseline.getMetrics().get(key);

                if (currentValue instanceof Number && baselineValue instanceof Number) {
                    double current = ((Number) currentValue).doubleValue();
                    double baselineNum = ((Number) baselineValue).doubleValue();
                    Map<String, Object> metricDiff = new HashMap<>();
                    metricDiff.put("baseline", baselineNum);
                    metricDiff.put("current", current);
                    metricDiff.put("absolute_diff", current - baselineNum);
                    metricDiff.put("relative_diff", baselineNum != 0 ? (current - baselineNum) / baselineNum : 0);
                    metricDiff.put("improvement", current > baselineNum);
                    diffs.put(key, metricDiff);
                }
            }
            comparison.put("metric_differences", diffs);
        }
        return comparison;
    }

    @Override
    public Mono<EvaluationMetric> getEvaluation(String evaluationId) {
        String cacheKey = EVAL_CACHE_PREFIX + evaluationId;
        return redisTemplate.opsForValue().get(cacheKey)
                .flatMap(json -> Mono.justOrEmpty(fromJson(json, EvaluationMetric.class)))
                .switchIfEmpty(Mono.fromCallable(() -> {
                    LambdaQueryWrapper<EvaluationMetric> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(EvaluationMetric::getEvaluationId, evaluationId);
                    EvaluationMetric eval = evaluationMetricMapper.selectOne(wrapper);
                    if (eval == null) {
                        throw new ResourceNotFoundException("Evaluation not found: " + evaluationId);
                    }
                    redisTemplate.opsForValue().set(cacheKey, toJson(eval), Duration.ofMinutes(30)).subscribe();
                    return eval;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    public Mono<Page<EvaluationMetric>> listEvaluations(int page, int size, String modelId, String version,
                                                         String evaluationType, String status) {
        return Mono.fromCallable(() -> {
            Page<EvaluationMetric> pageParam = new Page<>(page, size);
            LambdaQueryWrapper<EvaluationMetric> wrapper = new LambdaQueryWrapper<>();
            if (modelId != null) wrapper.eq(EvaluationMetric::getModelId, modelId);
            if (version != null) wrapper.eq(EvaluationMetric::getVersion, version);
            if (evaluationType != null) wrapper.eq(EvaluationMetric::getEvaluationType, evaluationType);
            if (status != null) wrapper.eq(EvaluationMetric::getStatus, status);
            wrapper.orderByDesc(EvaluationMetric::getCreatedAt);
            return evaluationMetricMapper.selectPage(pageParam, wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<String, Object>> compareEvaluations(List<String> evaluationIds) {
        return Flux.fromIterable(evaluationIds)
                .flatMap(this::getEvaluation)
                .collectList()
                .map(evaluations -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("evaluation_ids", evaluationIds);
                    result.put("evaluations", evaluations);

                    Set<String> allMetrics = new HashSet<>();
                    for (EvaluationMetric eval : evaluations) {
                        if (eval.getMetrics() != null) {
                            allMetrics.addAll(eval.getMetrics().keySet());
                        }
                    }

                    Map<String, List<Object>> metricValues = new LinkedHashMap<>();
                    for (String metric : allMetrics) {
                        List<Object> values = new ArrayList<>();
                        for (EvaluationMetric eval : evaluations) {
                            values.add(eval.getMetrics() != null ? eval.getMetrics().get(metric) : null);
                        }
                        metricValues.put(metric, values);
                    }
                    result.put("metric_values", metricValues);

                    return result;
                });
    }

    @Override
    public Mono<Map<String, Object>> compareModelVersions(String modelId, String version1, String version2, String evaluationType) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<EvaluationMetric> wrapper1 = new LambdaQueryWrapper<>();
            wrapper1.eq(EvaluationMetric::getModelId, modelId)
                    .eq(EvaluationMetric::getVersion, version1)
                    .eq(EvaluationMetric::getEvaluationType, evaluationType)
                    .orderByDesc(EvaluationMetric::getCreatedAt)
                    .last("LIMIT 1");
            EvaluationMetric eval1 = evaluationMetricMapper.selectOne(wrapper1);

            LambdaQueryWrapper<EvaluationMetric> wrapper2 = new LambdaQueryWrapper<>();
            wrapper2.eq(EvaluationMetric::getModelId, modelId)
                    .eq(EvaluationMetric::getVersion, version2)
                    .eq(EvaluationMetric::getEvaluationType, evaluationType)
                    .orderByDesc(EvaluationMetric::getCreatedAt)
                    .last("LIMIT 1");
            EvaluationMetric eval2 = evaluationMetricMapper.selectOne(wrapper2);

            Map<String, Object> result = new HashMap<>();
            result.put("model_id", modelId);
            result.put("version_1", version1);
            result.put("version_2", version2);
            result.put("evaluation_type", evaluationType);
            result.put("eval_1", eval1);
            result.put("eval_2", eval2);

            if (eval1 != null && eval2 != null && eval1.getMetrics() != null && eval2.getMetrics() != null) {
                Map<String, Object> comparison = new HashMap<>();
                Set<String> allKeys = new HashSet<>();
                allKeys.addAll(eval1.getMetrics().keySet());
                allKeys.addAll(eval2.getMetrics().keySet());

                for (String key : allKeys) {
                    Object v1 = eval1.getMetrics().get(key);
                    Object v2 = eval2.getMetrics().get(key);
                    Map<String, Object> metricCompare = new HashMap<>();
                    metricCompare.put("v1", v1);
                    metricCompare.put("v2", v2);
                    if (v1 instanceof Number && v2 instanceof Number) {
                        double d1 = ((Number) v1).doubleValue();
                        double d2 = ((Number) v2).doubleValue();
                        metricCompare.put("diff", d2 - d1);
                        metricCompare.put("diff_percent", d1 != 0 ? ((d2 - d1) / d1) * 100 : 0);
                        metricCompare.put("improved", d2 > d1);
                    }
                    comparison.put(key, metricCompare);
                }
                result.put("metric_comparison", comparison);
            }

            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Void> deleteEvaluation(String evaluationId) {
        return getEvaluation(evaluationId)
                .flatMap(eval -> Mono.fromCallable(() -> {
                    evaluationMetricMapper.deleteById(eval.getId());
                    String cacheKey = EVAL_CACHE_PREFIX + evaluationId;
                    redisTemplate.delete(cacheKey).subscribe();
                    return null;
                }).subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<OnlineMonitoring> recordMonitoringData(MonitoringRecordDTO dto) {
        return Mono.fromCallable(() -> {
            String monitorId = "mon_" + IdUtil.simpleUUID();

            OnlineMonitoring monitoring = new OnlineMonitoring();
            monitoring.setMonitorId(monitorId);
            monitoring.setModelId(dto.getModelId());
            monitoring.setVersion(dto.getVersion());
            monitoring.setTimestamp(dto.getTimestamp() != null ? dto.getTimestamp() : LocalDateTime.now());
            monitoring.setMetrics(dto.getMetrics());
            monitoring.setPredictionDistribution(dto.getPredictionDistribution());
            monitoring.setFeatureDistribution(dto.getFeatureDistribution());
            monitoring.setRequestCount(dto.getRequestCount());
            monitoring.setSuccessCount(dto.getSuccessCount());
            monitoring.setErrorCount(dto.getErrorCount());
            monitoring.setAvgLatencyMs(dto.getAvgLatencyMs());
            monitoring.setP50LatencyMs(dto.getP50LatencyMs());
            monitoring.setP95LatencyMs(dto.getP95LatencyMs());
            monitoring.setP99LatencyMs(dto.getP99LatencyMs());
            monitoring.setThroughput(dto.getThroughput());
            monitoring.setErrorRate(dto.getErrorRate());
            monitoring.setTimeWindow(dto.getTimeWindow() != null ? dto.getTimeWindow() : "5m");

            Map<String, Object> alerts = new HashMap<>();
            if (dto.getErrorRate() != null && dto.getErrorRate() > 0.05) {
                alerts.put("high_error_rate", Map.of("value", dto.getErrorRate(), "threshold", 0.05, "severity", "warning"));
            }
            if (dto.getP99LatencyMs() != null && dto.getP99LatencyMs() > 5000) {
                alerts.put("high_p99_latency", Map.of("value", dto.getP99LatencyMs(), "threshold", 5000, "severity", "warning"));
            }
            if (!alerts.isEmpty()) {
                monitoring.setAlerts(alerts);
            }

            onlineMonitoringMapper.insert(monitoring);
            monitoringRecordedCounter.increment();

            String cacheKey = MONITOR_CACHE_PREFIX + dto.getModelId() + ":" + dto.getVersion() + ":latest";
            redisTemplate.opsForValue().set(cacheKey, toJson(monitoring), Duration.ofMinutes(5)).subscribe();

            return monitoring;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<OnlineMonitoring> getLatestMonitoring(String modelId, String version) {
        String cacheKey = MONITOR_CACHE_PREFIX + modelId + ":" + version + ":latest";
        return redisTemplate.opsForValue().get(cacheKey)
                .flatMap(json -> Mono.justOrEmpty(fromJson(json, OnlineMonitoring.class)))
                .switchIfEmpty(Mono.fromCallable(() -> {
                    LambdaQueryWrapper<OnlineMonitoring> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(OnlineMonitoring::getModelId, modelId)
                            .eq(OnlineMonitoring::getVersion, version)
                            .orderByDesc(OnlineMonitoring::getTimestamp)
                            .last("LIMIT 1");
                    OnlineMonitoring monitoring = onlineMonitoringMapper.selectOne(wrapper);
                    if (monitoring == null) {
                        throw new ResourceNotFoundException("No monitoring data found for model: " + modelId + "@" + version);
                    }
                    redisTemplate.opsForValue().set(cacheKey, toJson(monitoring), Duration.ofMinutes(5)).subscribe();
                    return monitoring;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    public Mono<List<OnlineMonitoring>> getMonitoringHistory(String modelId, String version,
                                                              LocalDateTime startTime, LocalDateTime endTime,
                                                              String timeWindow) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<OnlineMonitoring> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(OnlineMonitoring::getModelId, modelId);
            if (version != null) wrapper.eq(OnlineMonitoring::getVersion, version);
            if (startTime != null) wrapper.ge(OnlineMonitoring::getTimestamp, startTime);
            if (endTime != null) wrapper.le(OnlineMonitoring::getTimestamp, endTime);
            if (timeWindow != null) wrapper.eq(OnlineMonitoring::getTimeWindow, timeWindow);
            wrapper.orderByAsc(OnlineMonitoring::getTimestamp);
            return onlineMonitoringMapper.selectList(wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<String, Object>> getMonitoringSummary(String modelId, String version,
                                                           LocalDateTime startTime, LocalDateTime endTime) {
        return getMonitoringHistory(modelId, version, startTime, endTime, null)
                .map(records -> {
                    Map<String, Object> summary = new HashMap<>();
                    summary.put("model_id", modelId);
                    summary.put("version", version);
                    summary.put("time_range_start", startTime);
                    summary.put("time_range_end", endTime);
                    summary.put("total_records", records.size());

                    if (!records.isEmpty()) {
                        LongSummaryStatistics requestStats = records.stream()
                                .filter(r -> r.getRequestCount() != null)
                                .mapToLong(OnlineMonitoring::getRequestCount)
                                .summaryStatistics();
                        LongSummaryStatistics errorStats = records.stream()
                                .filter(r -> r.getErrorCount() != null)
                                .mapToLong(OnlineMonitoring::getErrorCount)
                                .summaryStatistics();
                        DoubleSummaryStatistics latencyStats = records.stream()
                                .filter(r -> r.getAvgLatencyMs() != null)
                                .mapToDouble(OnlineMonitoring::getAvgLatencyMs)
                                .summaryStatistics();
                        DoubleSummaryStatistics throughputStats = records.stream()
                                .filter(r -> r.getThroughput() != null)
                                .mapToDouble(OnlineMonitoring::getThroughput)
                                .summaryStatistics();

                        summary.put("total_requests", requestStats.getSum());
                        summary.put("total_errors", errorStats.getSum());
                        summary.put("avg_error_rate", requestStats.getSum() > 0 ? (double) errorStats.getSum() / requestStats.getSum() : 0);
                        summary.put("avg_latency_ms", latencyStats.getAverage());
                        summary.put("max_latency_ms", latencyStats.getMax());
                        summary.put("avg_throughput", throughputStats.getAverage());
                        summary.put("peak_throughput", throughputStats.getMax());

                        List<Map<String, Object>> alerts = records.stream()
                                .filter(r -> r.getAlerts() != null && !r.getAlerts().isEmpty())
                                .map(r -> {
                                    Map<String, Object> alertEntry = new HashMap<>();
                                    alertEntry.put("timestamp", r.getTimestamp());
                                    alertEntry.put("alerts", r.getAlerts());
                                    return alertEntry;
                                })
                                .collect(Collectors.toList());
                        summary.put("alerts", alerts);
                        summary.put("alert_count", alerts.size());
                    }

                    return summary;
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DriftDetection> detectDrift(DriftDetectionDTO dto) {
        return Mono.fromCallable(() -> {
            String detectionId = "drift_" + IdUtil.simpleUUID();

            double driftScore = 0.0;
            String driftStatus = "no_drift";
            String severity = "low";
            Map<String, Object> statisticalTests = new HashMap<>();

            if (dto.getBaselineDistribution() != null && dto.getCurrentDistribution() != null) {
                double psi = calculatePSI(dto.getBaselineDistribution(), dto.getCurrentDistribution());
                double ks = calculateKSStatistic(dto.getBaselineDistribution(), dto.getCurrentDistribution());
                double wasserstein = calculateWassersteinDistance(dto.getBaselineDistribution(), dto.getCurrentDistribution());

                driftScore = psi;

                statisticalTests.put("psi", psi);
                statisticalTests.put("ks_statistic", ks);
                statisticalTests.put("wasserstein_distance", wasserstein);

                double threshold = dto.getThreshold() != null ? dto.getThreshold() : 0.2;

                if (psi >= threshold * 2) {
                    driftStatus = "critical_drift";
                    severity = "critical";
                    driftAlertCounter.increment();
                } else if (psi >= threshold) {
                    driftStatus = "drift_detected";
                    severity = "high";
                    driftAlertCounter.increment();
                } else if (psi >= threshold * 0.5) {
                    driftStatus = "potential_drift";
                    severity = "medium";
                } else {
                    driftStatus = "no_drift";
                    severity = "low";
                }
            }

            DriftDetection detection = new DriftDetection();
            detection.setDetectionId(detectionId);
            detection.setModelId(dto.getModelId());
            detection.setVersion(dto.getVersion());
            detection.setDriftType(dto.getDriftType() != null ? dto.getDriftType() : "feature_drift");
            detection.setFeatureName(dto.getFeatureName());
            detection.setDriftScore(driftScore);
            detection.setDriftStatus(driftStatus);
            detection.setThreshold(dto.getThreshold() != null ? dto.getThreshold() : 0.2);
            detection.setBaselineDistribution(dto.getBaselineDistribution());
            detection.setCurrentDistribution(dto.getCurrentDistribution());
            detection.setStatisticalTests(statisticalTests);
            detection.setDetectionTime(LocalDateTime.now());
            detection.setTimeWindow(dto.getTimeWindow() != null ? dto.getTimeWindow() : "1h");
            detection.setSeverity(severity);
            detection.setStatus("completed");
            detection.setMetadata(dto.getMetadata());

            if (!"no_drift".equals(driftStatus)) {
                driftDetectedCounter.increment();
                List<String> recommendedActions = new ArrayList<>();
                if ("critical_drift".equals(driftStatus)) {
                    recommendedActions.add("Consider rolling back to previous model version");
                    recommendedActions.add("Trigger model retraining pipeline");
                    recommendedActions.add("Notify model owner immediately");
                } else if ("drift_detected".equals(driftStatus)) {
                    recommendedActions.add("Investigate data distribution changes");
                    recommendedActions.add("Schedule model retraining");
                    recommendedActions.add("Monitor closely for further drift");
                } else {
                    recommendedActions.add("Continue monitoring");
                    recommendedActions.add("Review data collection pipeline");
                }
                Map<String, Object> actions = new HashMap<>();
                actions.put("recommendations", recommendedActions);
                detection.setRecommendedActions(actions);
            }

            driftDetectionMapper.insert(detection);

            String cacheKey = DRIFT_CACHE_PREFIX + detectionId;
            redisTemplate.opsForValue().set(cacheKey, toJson(detection), Duration.ofMinutes(30)).subscribe();

            log.info("Drift detection completed: {} status={} score={} for feature={}",
                    detectionId, driftStatus, driftScore, dto.getFeatureName());
            return detection;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<DriftDetection> getDriftDetection(String detectionId) {
        String cacheKey = DRIFT_CACHE_PREFIX + detectionId;
        return redisTemplate.opsForValue().get(cacheKey)
                .flatMap(json -> Mono.justOrEmpty(fromJson(json, DriftDetection.class)))
                .switchIfEmpty(Mono.fromCallable(() -> {
                    LambdaQueryWrapper<DriftDetection> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(DriftDetection::getDetectionId, detectionId);
                    DriftDetection detection = driftDetectionMapper.selectOne(wrapper);
                    if (detection == null) {
                        throw new ResourceNotFoundException("Drift detection not found: " + detectionId);
                    }
                    redisTemplate.opsForValue().set(cacheKey, toJson(detection), Duration.ofMinutes(30)).subscribe();
                    return detection;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    public Mono<Page<DriftDetection>> listDriftDetections(int page, int size, String modelId, String version,
                                                           String driftType, String driftStatus, String severity) {
        return Mono.fromCallable(() -> {
            Page<DriftDetection> pageParam = new Page<>(page, size);
            LambdaQueryWrapper<DriftDetection> wrapper = new LambdaQueryWrapper<>();
            if (modelId != null) wrapper.eq(DriftDetection::getModelId, modelId);
            if (version != null) wrapper.eq(DriftDetection::getVersion, version);
            if (driftType != null) wrapper.eq(DriftDetection::getDriftType, driftType);
            if (driftStatus != null) wrapper.eq(DriftDetection::getDriftStatus, driftStatus);
            if (severity != null) wrapper.eq(DriftDetection::getSeverity, severity);
            wrapper.orderByDesc(DriftDetection::getDetectionTime);
            return driftDetectionMapper.selectPage(pageParam, wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<DriftDetection>> detectAllDrifts(DriftDetectionDTO dto) {
        if (dto.getCurrentDistribution() == null || dto.getBaselineDistribution() == null) {
            return Mono.just(Collections.emptyList());
        }

        List<Map.Entry<String, Object>> features = new ArrayList<>();
        if (dto.getFeatureName() != null) {
            features.add(new AbstractMap.SimpleEntry<>(dto.getFeatureName(), null));
        } else {
            for (Map.Entry<String, Object> entry : dto.getCurrentDistribution().entrySet()) {
                if (dto.getBaselineDistribution().containsKey(entry.getKey())) {
                    features.add(entry);
                }
            }
        }

        return Flux.fromIterable(features)
                .flatMap(feature -> {
                    DriftDetectionDTO featureDto = new DriftDetectionDTO();
                    featureDto.setModelId(dto.getModelId());
                    featureDto.setVersion(dto.getVersion());
                    featureDto.setDriftType(dto.getDriftType());
                    featureDto.setFeatureName(feature.getKey());
                    featureDto.setThreshold(dto.getThreshold());
                    featureDto.setTimeWindow(dto.getTimeWindow());
                    featureDto.setMetadata(dto.getMetadata());

                    Object baselineValue = dto.getBaselineDistribution().get(feature.getKey());
                    Object currentValue = feature.getValue();
                    if (currentValue == null) {
                        currentValue = dto.getCurrentDistribution().get(feature.getKey());
                    }

                    if (baselineValue instanceof Map && currentValue instanceof Map) {
                        featureDto.setBaselineDistribution((Map<String, Object>) baselineValue);
                        featureDto.setCurrentDistribution((Map<String, Object>) currentValue);
                    } else {
                        featureDto.setBaselineDistribution(Map.of("value", baselineValue));
                        featureDto.setCurrentDistribution(Map.of("value", currentValue));
                    }

                    return detectDrift(featureDto);
                })
                .collectList();
    }

    @Override
    public Mono<Map<String, Object>> getDriftSummary(String modelId, String version,
                                                      LocalDateTime startTime, LocalDateTime endTime) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<DriftDetection> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DriftDetection::getModelId, modelId);
            if (version != null) wrapper.eq(DriftDetection::getVersion, version);
            if (startTime != null) wrapper.ge(DriftDetection::getDetectionTime, startTime);
            if (endTime != null) wrapper.le(DriftDetection::getDetectionTime, endTime);
            wrapper.orderByDesc(DriftDetection::getDetectionTime);
            List<DriftDetection> detections = driftDetectionMapper.selectList(wrapper);

            Map<String, Object> summary = new HashMap<>();
            summary.put("model_id", modelId);
            summary.put("version", version);
            summary.put("time_range_start", startTime);
            summary.put("time_range_end", endTime);
            summary.put("total_detections", detections.size());

            Map<String, Long> statusCounts = detections.stream()
                    .collect(Collectors.groupingBy(
                            d -> d.getDriftStatus() != null ? d.getDriftStatus() : "unknown",
                            Collectors.counting()
                    ));
            summary.put("status_distribution", statusCounts);

            Map<String, Long> severityCounts = detections.stream()
                    .collect(Collectors.groupingBy(
                            d -> d.getSeverity() != null ? d.getSeverity() : "unknown",
                            Collectors.counting()
                    ));
            summary.put("severity_distribution", severityCounts);

            List<DriftDetection> activeAlerts = detections.stream()
                    .filter(d -> !"no_drift".equals(d.getDriftStatus()))
                    .limit(10)
                    .collect(Collectors.toList());
            summary.put("active_alerts", activeAlerts);
            summary.put("alert_count", activeAlerts.size());

            DoubleSummaryStatistics scoreStats = detections.stream()
                    .filter(d -> d.getDriftScore() != null)
                    .mapToDouble(DriftDetection::getDriftScore)
                    .summaryStatistics();
            summary.put("avg_drift_score", scoreStats.getAverage());
            summary.put("max_drift_score", scoreStats.getMax());

            Set<String> driftedFeatures = detections.stream()
                    .filter(d -> !"no_drift".equals(d.getDriftStatus()) && d.getFeatureName() != null)
                    .map(DriftDetection::getFeatureName)
                    .collect(Collectors.toSet());
            summary.put("drifted_features", driftedFeatures);
            summary.put("drifted_feature_count", driftedFeatures.size());

            return summary;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Scheduled(fixedRate = 3600000)
    public Flux<DriftDetection> scheduledDriftDetection() {
        log.info("Starting scheduled drift detection");
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<OnlineMonitoring> wrapper = new LambdaQueryWrapper<>();
            wrapper.groupBy(OnlineMonitoring::getModelId, OnlineMonitoring::getVersion)
                    .orderByDesc(OnlineMonitoring::getTimestamp);
            return onlineMonitoringMapper.selectList(wrapper);
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .distinct(m -> m.getModelId() + ":" + m.getVersion())
                .take(10)
                .flatMap(monitoring -> {
                    if (monitoring.getFeatureDistribution() == null) {
                        return Mono.empty();
                    }

                    DriftDetectionDTO dto = new DriftDetectionDTO();
                    dto.setModelId(monitoring.getModelId());
                    dto.setVersion(monitoring.getVersion());
                    dto.setCurrentDistribution(monitoring.getFeatureDistribution());
                    dto.setBaselineDistribution(monitoring.getFeatureDistribution());
                    dto.setThreshold(0.2);
                    dto.setTimeWindow("1h");

                    return detectAllDrifts(dto)
                            .flatMapMany(Flux::fromIterable);
                })
                .onErrorContinue((e, o) -> log.error("Scheduled drift detection failed: {}", e.getMessage()));
    }

    @Override
    @Scheduled(fixedRate = 300000)
    public Flux<OnlineMonitoring> aggregateMonitoringData() {
        log.info("Starting 5-minute monitoring aggregation");
        return Flux.empty();
    }

    @Override
    public Mono<Map<String, Object>> getModelDashboard(String modelId, String version) {
        LocalDateTime startTime = LocalDateTime.now().minusDays(7);
        LocalDateTime endTime = LocalDateTime.now();

        return Mono.zip(
                getLatestMonitoring(modelId, version).onErrorResume(e -> Mono.empty()),
                getMonitoringSummary(modelId, version, startTime, endTime).onErrorResume(e -> Mono.empty()),
                getDriftSummary(modelId, version, startTime, endTime).onErrorResume(e -> Mono.empty()),
                listEvaluations(1, 5, modelId, version, null, "completed").onErrorResume(e -> Mono.empty())
        ).map(tuple -> {
            Map<String, Object> dashboard = new HashMap<>();
            dashboard.put("model_id", modelId);
            dashboard.put("version", version);
            dashboard.put("latest_monitoring", tuple.getT1());
            dashboard.put("monitoring_summary_7d", tuple.getT2());
            dashboard.put("drift_summary_7d", tuple.getT3());
            dashboard.put("recent_evaluations", tuple.getT4() != null ? tuple.getT4().getRecords() : Collections.emptyList());
            return dashboard;
        });
    }

    @Override
    public Mono<Map<String, Object>> getOverallDashboard(LocalDateTime startTime, LocalDateTime endTime) {
        return Mono.fromCallable(() -> {
            LocalDateTime actualStart = startTime != null ? startTime : LocalDateTime.now().minusDays(7);
            LocalDateTime actualEnd = endTime != null ? endTime : LocalDateTime.now();

            LambdaQueryWrapper<OnlineMonitoring> monitorWrapper = new LambdaQueryWrapper<>();
            monitorWrapper.ge(OnlineMonitoring::getTimestamp, actualStart)
                    .le(OnlineMonitoring::getTimestamp, actualEnd);
            List<OnlineMonitoring> allMonitoring = onlineMonitoringMapper.selectList(monitorWrapper);

            LambdaQueryWrapper<DriftDetection> driftWrapper = new LambdaQueryWrapper<>();
            driftWrapper.ge(DriftDetection::getDetectionTime, actualStart)
                    .le(DriftDetection::getDetectionTime, actualEnd);
            List<DriftDetection> allDrift = driftDetectionMapper.selectList(driftWrapper);

            LambdaQueryWrapper<EvaluationMetric> evalWrapper = new LambdaQueryWrapper<>();
            evalWrapper.ge(EvaluationMetric::getCreatedAt, actualStart)
                    .le(EvaluationMetric::getCreatedAt, actualEnd);
            List<EvaluationMetric> allEvals = evaluationMetricMapper.selectList(evalWrapper);

            Map<String, Object> dashboard = new HashMap<>();
            dashboard.put("time_range_start", actualStart);
            dashboard.put("time_range_end", actualEnd);

            long totalRequests = allMonitoring.stream().mapToLong(m -> m.getRequestCount() != null ? m.getRequestCount() : 0).sum();
            long totalErrors = allMonitoring.stream().mapToLong(m -> m.getErrorCount() != null ? m.getErrorCount() : 0).sum();
            double avgLatency = allMonitoring.stream().mapToDouble(m -> m.getAvgLatencyMs() != null ? m.getAvgLatencyMs() : 0).average().orElse(0);

            dashboard.put("total_requests", totalRequests);
            dashboard.put("total_errors", totalErrors);
            dashboard.put("error_rate", totalRequests > 0 ? (double) totalErrors / totalRequests : 0);
            dashboard.put("avg_latency_ms", avgLatency);

            long driftCount = allDrift.stream().filter(d -> !"no_drift".equals(d.getDriftStatus())).count();
            long criticalDriftCount = allDrift.stream().filter(d -> "critical_drift".equals(d.getDriftStatus())).count();
            dashboard.put("total_drift_detections", allDrift.size());
            dashboard.put("active_drift_alerts", driftCount);
            dashboard.put("critical_drift_alerts", criticalDriftCount);

            dashboard.put("total_evaluations", allEvals.size());

            Set<String> modelsMonitored = allMonitoring.stream()
                    .map(m -> m.getModelId() + "@" + m.getVersion())
                    .collect(Collectors.toSet());
            dashboard.put("models_monitored", modelsMonitored.size());
            dashboard.put("model_list", modelsMonitored);

            return dashboard;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public double calculateKSStatistic(Map<String, Object> dist1, Map<String, Object> dist2) {
        List<Double> values1 = extractNumericValues(dist1);
        List<Double> values2 = extractNumericValues(dist2);

        if (values1.isEmpty() || values2.isEmpty()) {
            return 0.0;
        }

        Collections.sort(values1);
        Collections.sort(values2);

        int n1 = values1.size();
        int n2 = values2.size();
        int i = 0, j = 0;
        double maxDiff = 0.0;
        double cdf1 = 0.0, cdf2 = 0.0;

        while (i < n1 && j < n2) {
            double v1 = values1.get(i);
            double v2 = values2.get(j);

            if (v1 <= v2) {
                cdf1 = (double) (i + 1) / n1;
                i++;
            } else {
                cdf2 = (double) (j + 1) / n2;
                j++;
            }

            maxDiff = Math.max(maxDiff, Math.abs(cdf1 - cdf2));
        }

        return maxDiff;
    }

    @Override
    public double calculateWassersteinDistance(Map<String, Object> dist1, Map<String, Object> dist2) {
        List<Double> values1 = extractNumericValues(dist1);
        List<Double> values2 = extractNumericValues(dist2);

        if (values1.isEmpty() || values2.isEmpty()) {
            return 0.0;
        }

        Collections.sort(values1);
        Collections.sort(values2);

        int n1 = values1.size();
        int n2 = values2.size();
        int n = Math.min(n1, n2);

        double distance = 0.0;
        for (int i = 0; i < n; i++) {
            distance += Math.abs(values1.get((int) ((double) i / n * n1)) - values2.get((int) ((double) i / n * n2)));
        }

        return distance / n;
    }

    @Override
    public double calculatePSI(Map<String, Object> expected, Map<String, Object> actual) {
        if (expected == null || actual == null) {
            return 0.0;
        }

        double psi = 0.0;

        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String key = entry.getKey();
            double expectedRatio = getNumericValue(entry.getValue());
            double actualRatio = actual.containsKey(key) ? getNumericValue(actual.get(key)) : 0.0001;

            if (expectedRatio == 0) {
                expectedRatio = 0.0001;
            }
            if (actualRatio == 0) {
                actualRatio = 0.0001;
            }

            double diff = actualRatio - expectedRatio;
            double lnRatio = Math.log(actualRatio / expectedRatio);
            psi += diff * lnRatio;
        }

        for (Map.Entry<String, Object> entry : actual.entrySet()) {
            if (!expected.containsKey(entry.getKey())) {
                double expectedRatio = 0.0001;
                double actualRatio = getNumericValue(entry.getValue());
                if (actualRatio == 0) {
                    actualRatio = 0.0001;
                }
                double diff = actualRatio - expectedRatio;
                double lnRatio = Math.log(actualRatio / expectedRatio);
                psi += diff * lnRatio;
            }
        }

        return psi;
    }

    private List<Double> extractNumericValues(Map<String, Object> dist) {
        List<Double> values = new ArrayList<>();
        for (Object value : dist.values()) {
            if (value instanceof Number) {
                values.add(((Number) value).doubleValue());
            } else if (value instanceof Map) {
                values.addAll(extractNumericValues((Map<String, Object>) value));
            } else if (value instanceof List) {
                for (Object item : (List<?>) value) {
                    if (item instanceof Number) {
                        values.add(((Number) item).doubleValue());
                    }
                }
            }
        }
        return values;
    }

    private double getNumericValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BusinessException("Failed to serialize object", e);
        }
    }

    private <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            return null;
        }
    }
}
