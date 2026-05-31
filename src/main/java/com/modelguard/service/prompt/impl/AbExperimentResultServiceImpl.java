package com.modelguard.service.prompt.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.modelguard.converter.EntityConverter;
import com.modelguard.dto.request.ExperimentResultRecordRequest;
import com.modelguard.dto.response.AbExperimentResultResponse;
import com.modelguard.entity.AbExperimentResult;
import com.modelguard.exception.BusinessException;
import com.modelguard.mapper.AbExperimentResultMapper;
import com.modelguard.service.prompt.AbExperimentResultService;
import com.modelguard.service.prompt.AbExperimentService;
import com.modelguard.util.IdGeneratorUtil;
import com.modelguard.util.ReactiveBridgeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AbExperimentResultServiceImpl implements AbExperimentResultService {

    private final AbExperimentResultMapper experimentResultMapper;
    private final AbExperimentService experimentService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<AbExperimentResultResponse> recordResult(ExperimentResultRecordRequest request) {
        return experimentService.ensureExperimentRunning(request.getExperimentId())
                .flatMap(experiment -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    AbExperimentResult result = EntityConverter.toEntity(request);
                    result.setResultId("result_" + IdGeneratorUtil.generateSimpleId());

                    experimentResultMapper.insert(result);
                    log.info("Recorded experiment result: resultId={}, experimentId={}, groupId={}",
                            result.getResultId(), request.getExperimentId(), request.getGroupId());
                    return EntityConverter.toResponse(result);
                }));
    }

    @Override
    public Mono<List<AbExperimentResultResponse>> getResultsByExperiment(String experimentId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<AbExperimentResult> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AbExperimentResult::getExperimentId, experimentId)
                    .orderByDesc(AbExperimentResult::getCreatedAt);
            return experimentResultMapper.selectList(wrapper).stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());
        });
    }

    @Override
    public Mono<List<AbExperimentResultResponse>> getResultsByGroup(String experimentId, String groupId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<AbExperimentResult> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AbExperimentResult::getExperimentId, experimentId)
                    .eq(AbExperimentResult::getGroupId, groupId)
                    .orderByDesc(AbExperimentResult::getCreatedAt);
            return experimentResultMapper.selectList(wrapper).stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());
        });
    }

    @Override
    public Mono<Long> countResultsByGroup(String experimentId, String groupId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<AbExperimentResult> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AbExperimentResult::getExperimentId, experimentId)
                    .eq(AbExperimentResult::getGroupId, groupId);
            return experimentResultMapper.selectCount(wrapper);
        });
    }

    @Override
    public Mono<Map<String, Object>> calculateGroupMetrics(String experimentId, String groupId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<AbExperimentResult> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AbExperimentResult::getExperimentId, experimentId)
                    .eq(AbExperimentResult::getGroupId, groupId);
            List<AbExperimentResult> results = experimentResultMapper.selectList(wrapper);

            Map<String, Object> metrics = new HashMap<>();
            if (results.isEmpty()) {
                metrics.put("count", 0);
                metrics.put("avg_latency", 0);
                metrics.put("avg_input_tokens", 0);
                metrics.put("avg_output_tokens", 0);
                metrics.put("avg_score", 0);
                metrics.put("success_rate", 0);
                return metrics;
            }

            long count = results.size();
            double totalLatency = 0;
            long totalInputTokens = 0;
            long totalOutputTokens = 0;
            double totalScore = 0;
            long successCount = 0;

            for (AbExperimentResult result : results) {
                if (result.getLatencyMs() != null) {
                    totalLatency += result.getLatencyMs();
                }
                if (result.getInputTokens() != null) {
                    totalInputTokens += result.getInputTokens();
                }
                if (result.getOutputTokens() != null) {
                    totalOutputTokens += result.getOutputTokens();
                }
                if (result.getScores() != null && result.getScores().get("overall") instanceof Number) {
                    totalScore += ((Number) result.getScores().get("overall")).doubleValue();
                }
                if (result.getScores() != null && Boolean.TRUE.equals(result.getScores().get("success"))) {
                    successCount++;
                }
            }

            metrics.put("count", count);
            metrics.put("avg_latency", count > 0 ? totalLatency / count : 0);
            metrics.put("avg_input_tokens", count > 0 ? (double) totalInputTokens / count : 0);
            metrics.put("avg_output_tokens", count > 0 ? (double) totalOutputTokens / count : 0);
            metrics.put("avg_score", count > 0 ? totalScore / count : 0);
            metrics.put("success_rate", count > 0 ? (double) successCount / count : 0);
            metrics.put("total_tokens", totalInputTokens + totalOutputTokens);

            return metrics;
        });
    }

    @Override
    public Mono<Double> calculateMetricAverage(String experimentId, String groupId, String metricName) {
        return getResultsByGroup(experimentId, groupId)
                .map(results -> {
                    if (results.isEmpty()) return 0.0;

                    double total = 0;
                    int count = 0;

                    for (AbExperimentResultResponse result : results) {
                        switch (metricName) {
                            case "latency":
                                if (result.getLatencyMs() != null) {
                                    total += result.getLatencyMs();
                                    count++;
                                }
                                break;
                            case "input_tokens":
                                if (result.getInputTokens() != null) {
                                    total += result.getInputTokens();
                                    count++;
                                }
                                break;
                            case "output_tokens":
                                if (result.getOutputTokens() != null) {
                                    total += result.getOutputTokens();
                                    count++;
                                }
                                break;
                            default:
                                if (result.getScores() != null && result.getScores().get(metricName) instanceof Number) {
                                    total += ((Number) result.getScores().get(metricName)).doubleValue();
                                    count++;
                                }
                        }
                    }

                    return count > 0 ? total / count : 0.0;
                });
    }

    @Override
    public Mono<Map<String, Long>> getResultCountsByGroup(String experimentId) {
        return Mono.zip(
                countResultsByGroup(experimentId, "control"),
                countResultsByGroup(experimentId, "experimental"),
                countResultsByGroup(experimentId, "none")
        ).map(tuple -> {
            Map<String, Long> counts = new HashMap<>();
            counts.put("control", tuple.getT1());
            counts.put("experimental", tuple.getT2());
            counts.put("none", tuple.getT3());
            return counts;
        });
    }
}
