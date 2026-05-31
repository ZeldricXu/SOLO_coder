package com.modelguard.service.prompt.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.PageResult;
import com.modelguard.converter.EntityConverter;
import com.modelguard.dto.request.AbExperimentCreateRequest;
import com.modelguard.dto.response.AbExperimentResponse;
import com.modelguard.dto.response.ExperimentComparisonResponse;
import com.modelguard.entity.AbExperiment;
import com.modelguard.exception.BusinessException;
import com.modelguard.exception.ResourceNotFoundException;
import com.modelguard.mapper.AbExperimentMapper;
import com.modelguard.service.prompt.AbExperimentResultService;
import com.modelguard.service.prompt.AbExperimentService;
import com.modelguard.service.prompt.TrafficAssignmentService;
import com.modelguard.util.IdGeneratorUtil;
import com.modelguard.util.ReactiveBridgeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AbExperimentServiceImpl implements AbExperimentService {

    private final AbExperimentMapper abExperimentMapper;
    private final TrafficAssignmentService trafficAssignmentService;
    private final AbExperimentResultService experimentResultService;

    private static final List<String> VALID_STATUSES = Arrays.asList("DRAFT", "RUNNING", "PAUSED", "STOPPED");
    private static final List<String> GROUPS = Arrays.asList("control", "experimental");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<AbExperimentResponse> createExperiment(AbExperimentCreateRequest request) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            AbExperiment experiment = EntityConverter.toEntity(request);
            experiment.setExperimentId(IdGeneratorUtil.generateExperimentId());

            abExperimentMapper.insert(experiment);
            log.info("Created AB experiment: experimentId={}, name={}", experiment.getExperimentId(), experiment.getName());
            return EntityConverter.toResponse(experiment);
        });
    }

    @Override
    public Mono<AbExperimentResponse> getExperiment(String experimentId) {
        return getExperimentEntity(experimentId)
                .map(EntityConverter::toResponse);
    }

    @Override
    public Mono<AbExperiment> getExperimentEntity(String experimentId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<AbExperiment> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AbExperiment::getExperimentId, experimentId);
            AbExperiment experiment = abExperimentMapper.selectOne(wrapper);
            if (experiment == null) {
                throw new ResourceNotFoundException("AbExperiment", experimentId);
            }
            return experiment;
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<AbExperimentResponse> startExperiment(String experimentId) {
        return getExperimentEntity(experimentId)
                .flatMap(experiment -> {
                    if (!"DRAFT".equals(experiment.getStatus()) && !"PAUSED".equals(experiment.getStatus())) {
                        throw new BusinessException("Experiment cannot be started from status: " + experiment.getStatus());
                    }
                    return ReactiveBridgeUtil.monoFromCallable(() -> {
                        experiment.setStatus("RUNNING");
                        if (experiment.getStartedAt() == null) {
                            experiment.setStartedAt(LocalDateTime.now());
                        }
                        abExperimentMapper.updateById(experiment);
                        log.info("Started AB experiment: experimentId={}", experimentId);
                        return EntityConverter.toResponse(experiment);
                    });
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<AbExperimentResponse> pauseExperiment(String experimentId) {
        return getExperimentEntity(experimentId)
                .flatMap(experiment -> {
                    if (!"RUNNING".equals(experiment.getStatus())) {
                        throw new BusinessException("Experiment is not running, cannot pause");
                    }
                    return ReactiveBridgeUtil.monoFromCallable(() -> {
                        experiment.setStatus("PAUSED");
                        abExperimentMapper.updateById(experiment);
                        log.info("Paused AB experiment: experimentId={}", experimentId);
                        return EntityConverter.toResponse(experiment);
                    });
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<AbExperimentResponse> stopExperiment(String experimentId) {
        return getExperimentEntity(experimentId)
                .flatMap(experiment -> {
                    if ("STOPPED".equals(experiment.getStatus())) {
                        return Mono.just(EntityConverter.toResponse(experiment));
                    }
                    return ReactiveBridgeUtil.monoFromCallable(() -> {
                        experiment.setStatus("STOPPED");
                        experiment.setEndedAt(LocalDateTime.now());
                        abExperimentMapper.updateById(experiment);
                        log.info("Stopped AB experiment: experimentId={}", experimentId);
                        return EntityConverter.toResponse(experiment);
                    });
                });
    }

    @Override
    public Mono<PageResult<AbExperimentResponse>> pageExperiments(String status, int pageNum, int pageSize) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            Page<AbExperiment> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<AbExperiment> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isEmpty()) {
                wrapper.eq(AbExperiment::getStatus, status);
            }
            wrapper.orderByDesc(AbExperiment::getCreatedAt);
            Page<AbExperiment> result = abExperimentMapper.selectPage(page, wrapper);

            List<AbExperimentResponse> responses = result.getRecords().stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());

            return PageResult.of(responses, result.getTotal(), pageNum, pageSize);
        });
    }

    @Override
    public Mono<String> assignExperimentGroup(String experimentId, String userId) {
        return ensureExperimentRunning(experimentId)
                .flatMap(experiment -> {
                    BigDecimal trafficSplit = experiment.getTrafficSplit();
                    double ratio = trafficSplit != null ? trafficSplit.doubleValue() : 0.5;

                    return trafficAssignmentService.isInTraffic(userId, experimentId, ratio)
                            .flatMap(inTraffic -> {
                                if (!inTraffic) {
                                    return Mono.just("none");
                                }
                                return trafficAssignmentService.assignGroup(userId, experimentId, GROUPS);
                            });
                });
    }

    @Override
    public Mono<Map<String, Object>> validateExperimentStatus(String experimentId) {
        return getExperimentEntity(experimentId)
                .map(experiment -> {
                    Map<String, Object> validation = new HashMap<>();
                    validation.put("experimentId", experimentId);
                    validation.put("status", experiment.getStatus());
                    validation.put("isRunning", "RUNNING".equals(experiment.getStatus()));
                    validation.put("isValid", VALID_STATUSES.contains(experiment.getStatus()));
                    validation.put("canStart", "DRAFT".equals(experiment.getStatus()) || "PAUSED".equals(experiment.getStatus()));
                    validation.put("canStop", !"STOPPED".equals(experiment.getStatus()));
                    return validation;
                });
    }

    @Override
    public Mono<ExperimentComparisonResponse> compareExperimentResults(String experimentId) {
        return getExperimentEntity(experimentId)
                .flatMap(experiment ->
                        experimentResultService.getResultCountsByGroup(experimentId)
                                .zipWith(experimentResultService.calculateGroupMetrics(experimentId, "control"))
                                .zipWith(experimentResultService.calculateGroupMetrics(experimentId, "experimental"))
                                .map(tuple3 -> {
                                    Map<String, Long> counts = tuple3.getT1().getT1();
                                    Map<String, Object> controlMetrics = tuple3.getT1().getT2();
                                    Map<String, Object> experimentalMetrics = tuple3.getT2();

                                    Long controlCount = counts.getOrDefault("control", 0L);
                                    Long experimentalCount = counts.getOrDefault("experimental", 0L);

                                    Map<String, Object> metricDeltas = calculateMetricDeltas(controlMetrics, experimentalMetrics);

                                    String winningGroup = determineWinningGroup(controlMetrics, experimentalMetrics);
                                    String confidenceLevel = calculateConfidenceLevel(controlCount, experimentalCount, controlMetrics, experimentalMetrics);

                                    return ExperimentComparisonResponse.builder()
                                            .experimentId(experimentId)
                                            .experimentName(experiment.getName())
                                            .controlGroupCount(controlCount)
                                            .experimentalGroupCount(experimentalCount)
                                            .controlGroupMetrics(controlMetrics)
                                            .experimentalGroupMetrics(experimentalMetrics)
                                            .metricDeltas(metricDeltas)
                                            .winningGroup(winningGroup)
                                            .confidenceLevel(confidenceLevel)
                                            .build();
                                })
                );
    }

    private Map<String, Object> calculateMetricDeltas(Map<String, Object> control, Map<String, Object> experimental) {
        Map<String, Object> deltas = new HashMap<>();
        for (Map.Entry<String, Object> entry : control.entrySet()) {
            String key = entry.getKey();
            if (entry.getValue() instanceof Number && experimental.get(key) instanceof Number) {
                double controlVal = ((Number) entry.getValue()).doubleValue();
                double experimentalVal = ((Number) experimental.get(key)).doubleValue();
                deltas.put(key, experimentalVal - controlVal);
                deltas.put(key + "_percent", controlVal > 0 ? ((experimentalVal - controlVal) / controlVal) * 100 : 0);
            }
        }
        return deltas;
    }

    private String determineWinningGroup(Map<String, Object> control, Map<String, Object> experimental) {
        double controlScore = calculateCompositeScore(control);
        double experimentalScore = calculateCompositeScore(experimental);

        if (experimentalScore > controlScore * 1.05) {
            return "experimental";
        } else if (controlScore > experimentalScore * 1.05) {
            return "control";
        } else {
            return "tie";
        }
    }

    private double calculateCompositeScore(Map<String, Object> metrics) {
        double score = 0;
        if (metrics.get("avg_latency") instanceof Number) {
            double latency = ((Number) metrics.get("avg_latency")).doubleValue();
            score += Math.max(0, 1000 - latency) * 0.4;
        }
        if (metrics.get("avg_score") instanceof Number) {
            score += ((Number) metrics.get("avg_score")).doubleValue() * 50 * 0.4;
        }
        if (metrics.get("success_rate") instanceof Number) {
            score += ((Number) metrics.get("success_rate")).doubleValue() * 100 * 0.2;
        }
        return score;
    }

    private String calculateConfidenceLevel(Long controlCount, Long experimentalCount,
                                            Map<String, Object> control, Map<String, Object> experimental) {
        long minCount = Math.min(controlCount, experimentalCount);
        if (minCount < 10) return "low";
        if (minCount < 100) return "medium";

        double avgScoreDelta = 0;
        if (control.get("avg_score") instanceof Number && experimental.get("avg_score") instanceof Number) {
            double controlScore = ((Number) control.get("avg_score")).doubleValue();
            double experimentalScore = ((Number) experimental.get("avg_score")).doubleValue();
            avgScoreDelta = Math.abs(experimentalScore - controlScore);
        }

        if (avgScoreDelta > 0.2 && minCount >= 500) return "high";
        if (avgScoreDelta > 0.1 && minCount >= 200) return "medium-high";
        return "medium";
    }

    @Override
    public Mono<AbExperiment> ensureExperimentRunning(String experimentId) {
        return getExperimentEntity(experimentId)
                .flatMap(experiment -> {
                    if (!"RUNNING".equals(experiment.getStatus())) {
                        throw new BusinessException("Experiment is not running: " + experiment.getStatus());
                    }
                    return Mono.just(experiment);
                });
    }
}
