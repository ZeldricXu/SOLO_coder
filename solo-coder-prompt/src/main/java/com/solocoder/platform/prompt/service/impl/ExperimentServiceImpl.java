package com.solocoder.platform.prompt.service.impl;

import com.solocoder.platform.common.exception.BusinessException;
import com.solocoder.platform.prompt.model.*;
import com.solocoder.platform.prompt.service.ExperimentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ExperimentServiceImpl implements ExperimentService {

    private final Map<String, ExperimentConfig> experimentStore = new ConcurrentHashMap<>();
    private final Map<String, List<ExperimentResult>> resultStore = new ConcurrentHashMap<>();

    @Override
    public ExperimentConfig createExperiment(ExperimentConfig config) {
        String experimentId = config.getExperimentId() != null ? config.getExperimentId() : UUID.randomUUID().toString();
        ExperimentConfig saved = ExperimentConfig.builder()
                .experimentId(experimentId)
                .name(config.getName())
                .description(config.getDescription())
                .promptId(config.getPromptId())
                .variants(config.getVariants())
                .status(ExperimentConfig.ExperimentStatus.DRAFT)
                .targetingRules(config.getTargetingRules())
                .createdAt(LocalDateTime.now())
                .build();
        experimentStore.put(experimentId, saved);
        log.info("Experiment created: id={}, name={}", experimentId, saved.getName());
        return saved;
    }

    @Override
    public Optional<ExperimentConfig> getExperiment(String experimentId) {
        return Optional.ofNullable(experimentStore.get(experimentId));
    }

    @Override
    public List<ExperimentConfig> listExperiments() {
        return new ArrayList<>(experimentStore.values());
    }

    @Override
    public ExperimentConfig startExperiment(String experimentId) {
        ExperimentConfig config = experimentStore.get(experimentId);
        if (config == null) throw new BusinessException("Experiment not found: " + experimentId);
        if (config.getStatus() != ExperimentConfig.ExperimentStatus.DRAFT &&
                config.getStatus() != ExperimentConfig.ExperimentStatus.PAUSED) {
            throw new BusinessException("Experiment cannot be started from status: " + config.getStatus());
        }
        config.setStatus(ExperimentConfig.ExperimentStatus.RUNNING);
        config.setStartedAt(LocalDateTime.now());
        log.info("Experiment started: id={}", experimentId);
        return config;
    }

    @Override
    public ExperimentConfig pauseExperiment(String experimentId) {
        ExperimentConfig config = experimentStore.get(experimentId);
        if (config == null) throw new BusinessException("Experiment not found: " + experimentId);
        config.setStatus(ExperimentConfig.ExperimentStatus.PAUSED);
        log.info("Experiment paused: id={}", experimentId);
        return config;
    }

    @Override
    public ExperimentResult recordResult(ExperimentResult result) {
        String resultId = result.getResultId() != null ? result.getResultId() : UUID.randomUUID().toString();
        ExperimentResult saved = ExperimentResult.builder()
                .resultId(resultId)
                .experimentId(result.getExperimentId())
                .variantId(result.getVariantId())
                .requestId(result.getRequestId())
                .score(result.getScore())
                .metrics(result.getMetrics())
                .feedback(result.getFeedback())
                .evaluatedAt(LocalDateTime.now())
                .build();
        resultStore.computeIfAbsent(result.getExperimentId(), k -> new ArrayList<>()).add(saved);
        log.info("Experiment result recorded: experiment={}, variant={}, score={}",
                result.getExperimentId(), result.getVariantId(), result.getScore());
        return saved;
    }

    @Override
    public ExperimentComparison compareResults(String experimentId) {
        ExperimentConfig config = experimentStore.get(experimentId);
        if (config == null) throw new BusinessException("Experiment not found: " + experimentId);

        List<ExperimentResult> results = resultStore.getOrDefault(experimentId, List.of());
        Map<String, List<ExperimentResult>> byVariant = results.stream()
                .collect(Collectors.groupingBy(ExperimentResult::getVariantId));

        List<ExperimentComparison.VariantStats> statsList = new ArrayList<>();
        String winner = null;
        double bestMean = Double.MIN_VALUE;

        for (ExperimentConfig.Variant variant : config.getVariants()) {
            List<ExperimentResult> variantResults = byVariant.getOrDefault(variant.getVariantId(), List.of());
            if (variantResults.isEmpty()) continue;

            double mean = variantResults.stream().mapToDouble(ExperimentResult::getScore).average().orElse(0);
            double variance = variantResults.stream()
                    .mapToDouble(r -> Math.pow(r.getScore() - mean, 2)).average().orElse(0);
            double stdDev = Math.sqrt(variance);
            double min = variantResults.stream().mapToDouble(ExperimentResult::getScore).min().orElse(0);
            double max = variantResults.stream().mapToDouble(ExperimentResult::getScore).max().orElse(0);

            statsList.add(ExperimentComparison.VariantStats.builder()
                    .variantId(variant.getVariantId())
                    .variantName(variant.getName())
                    .sampleSize(variantResults.size())
                    .meanScore(mean)
                    .stdDeviation(stdDev)
                    .minScore(min)
                    .maxScore(max)
                    .build());

            if (mean > bestMean) {
                bestMean = mean;
                winner = variant.getVariantId();
            }
        }

        double confidence = results.size() >= 30 ? 0.95 : results.size() >= 10 ? 0.80 : 0.50;

        return ExperimentComparison.builder()
                .experimentId(experimentId)
                .variantStats(statsList)
                .winner(winner)
                .confidence(confidence)
                .recommendation(winner != null ? "Variant " + winner + " shows better performance" : "Insufficient data")
                .build();
    }
}
