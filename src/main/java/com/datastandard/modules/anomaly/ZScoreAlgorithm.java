package com.datastandard.modules.anomaly;

import com.datastandard.common.util.IdGenerator;
import com.datastandard.modules.anomaly.dto.AlgorithmConfig;
import com.datastandard.modules.anomaly.dto.AnomalyDetectionRequest;
import com.datastandard.modules.anomaly.dto.AnomalyResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ZScoreAlgorithm implements DetectionAlgorithm {

    private static final String ALGORITHM_NAME = "Z-Score";
    private static final String ALGORITHM_TYPE = "Z_SCORE";
    private static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("3.0");
    private static final BigDecimal DEFAULT_MAD_MULTIPLIER = new BigDecimal("1.4826");

    @Override
    public String getAlgorithmName() {
        return ALGORITHM_NAME;
    }

    @Override
    public String getAlgorithmType() {
        return ALGORITHM_TYPE;
    }

    @Override
    public Mono<List<AnomalyResult>> detect(AnomalyDetectionRequest request, AlgorithmConfig config) {
        return isDataSufficient(request.getDataPoints(), config)
                .flatMap(sufficient -> {
                    if (!sufficient) {
                        log.warn("数据点不足，跳过Z-Score检测: metricCode={}, count={}",
                                request.getMetricCode(), request.getDataPoints().size());
                        return Mono.just(new ArrayList<AnomalyResult>());
                    }
                    return Mono.fromCallable(() -> performDetection(request, config))
                            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
                });
    }

    private List<AnomalyResult> performDetection(AnomalyDetectionRequest request, AlgorithmConfig config) {
        List<AnomalyResult> results = new ArrayList<>();
        List<BigDecimal> values = request.getDataPoints().stream()
                .map(AnomalyDetectionRequest.DataPoint::getValue)
                .toList();

        AlgorithmConfig.ZScoreConfig zScoreConfig = config.getZScoreConfig();
        boolean useModified = zScoreConfig != null && Boolean.TRUE.equals(zScoreConfig.getUseModifiedZScore());
        BigDecimal threshold = zScoreConfig != null && zScoreConfig.getZScoreThreshold() != null
                ? zScoreConfig.getZScoreThreshold()
                : (config.getThreshold() != null ? config.getThreshold() : DEFAULT_THRESHOLD);

        BigDecimal mean = calculateMean(values);
        BigDecimal deviation = useModified ? calculateMAD(values) : calculateStdDev(values, mean);

        if (useModified && zScoreConfig != null && zScoreConfig.getMadMultiplier() != null) {
            deviation = deviation.multiply(zScoreConfig.getMadMultiplier());
        } else if (useModified) {
            deviation = deviation.multiply(DEFAULT_MAD_MULTIPLIER);
        }

        if (deviation.compareTo(BigDecimal.ZERO) == 0) {
            log.info("方差为零，无异常可检测: metricCode={}", request.getMetricCode());
            return results;
        }

        for (int i = 0; i < request.getDataPoints().size(); i++) {
            AnomalyDetectionRequest.DataPoint dataPoint = request.getDataPoints().get(i);
            BigDecimal zScore = dataPoint.getValue().subtract(mean)
                    .divide(deviation, 6, RoundingMode.HALF_UP);
            BigDecimal absZScore = zScore.abs();

            if (absZScore.compareTo(threshold) > 0) {
                BigDecimal anomalyScore = absZScore.divide(threshold, 4, RoundingMode.HALF_UP)
                        .min(new BigDecimal("1.0"));
                BigDecimal confidence = calculateConfidence(absZScore, threshold);
                String severity = determineSeverity(absZScore, threshold);

                AnomalyResult result = AnomalyResult.builder()
                        .resultId(IdGenerator.generateStrId())
                        .detectionCode(request.getDetectionCode())
                        .metricCode(request.getMetricCode())
                        .entityId(request.getEntityId())
                        .instanceId(request.getInstanceId())
                        .isAnomaly(true)
                        .anomalyType(absZScore.compareTo(BigDecimal.ZERO) > 0 ? "SPIKE" : "DIP")
                        .severity(severity)
                        .confidence(confidence)
                        .anomalyScore(anomalyScore)
                        .threshold(threshold)
                        .expectedValue(mean)
                        .actualValue(dataPoint.getValue())
                        .detectedAt(LocalDateTime.now())
                        .windowStart(request.getWindowStart())
                        .windowEnd(request.getWindowEnd())
                        .algorithmType(ALGORITHM_TYPE)
                        .anomalyData(Map.of(
                                "zScore", zScore,
                                "mean", mean,
                                "deviation", deviation,
                                "useModified", useModified,
                                "dataPointIndex", i
                        ))
                        .description(String.format("Z-Score检测到异常: zScore=%.4f, 阈值=%.2f", zScore, threshold))
                        .suggestedAction("检查该数据点是否为真实异常，考虑调整阈值")
                        .build();
                results.add(result);
            }
        }

        log.info("Z-Score检测完成: metricCode={}, 异常数={}, 数据点总数={}",
                request.getMetricCode(), results.size(), values.size());
        return results;
    }

    @Override
    public Mono<BigDecimal> calculateAnomalyScore(List<BigDecimal> data, BigDecimal value, AlgorithmConfig config) {
        return Mono.fromCallable(() -> {
            if (data.size() < 3) return BigDecimal.ZERO;

            AlgorithmConfig.ZScoreConfig zScoreConfig = config.getZScoreConfig();
            boolean useModified = zScoreConfig != null && Boolean.TRUE.equals(zScoreConfig.getUseModifiedZScore());
            BigDecimal threshold = zScoreConfig != null && zScoreConfig.getZScoreThreshold() != null
                    ? zScoreConfig.getZScoreThreshold() : DEFAULT_THRESHOLD;

            BigDecimal mean = calculateMean(data);
            BigDecimal deviation = useModified ? calculateMAD(data) : calculateStdDev(data, mean);

            if (deviation.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

            BigDecimal zScore = value.subtract(mean).divide(deviation, 6, RoundingMode.HALF_UP).abs();
            return zScore.divide(threshold, 4, RoundingMode.HALF_UP).min(new BigDecimal("1.0"));
        });
    }

    private BigDecimal calculateMean(List<BigDecimal> values) {
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateStdDev(List<BigDecimal> values, BigDecimal mean) {
        BigDecimal sumSquaredDiff = values.stream()
                .map(v -> v.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal variance = sumSquaredDiff.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
    }

    private BigDecimal calculateMAD(List<BigDecimal> values) {
        BigDecimal median = calculateMedian(values);
        List<BigDecimal> absDeviations = values.stream()
                .map(v -> v.subtract(median).abs())
                .sorted()
                .toList();
        return calculateMedian(absDeviations);
    }

    private BigDecimal calculateMedian(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int size = sorted.size();
        if (size % 2 == 0) {
            return sorted.get(size / 2 - 1).add(sorted.get(size / 2))
                    .divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
        } else {
            return sorted.get(size / 2);
        }
    }

    private BigDecimal calculateConfidence(BigDecimal zScore, BigDecimal threshold) {
        BigDecimal ratio = zScore.divide(threshold, 4, RoundingMode.HALF_UP);
        BigDecimal confidence = ratio.multiply(new BigDecimal("0.5")).add(new BigDecimal("0.5"));
        return confidence.min(new BigDecimal("0.99"));
    }

    private String determineSeverity(BigDecimal zScore, BigDecimal threshold) {
        BigDecimal ratio = zScore.divide(threshold, 2, RoundingMode.HALF_UP);
        if (ratio.compareTo(new BigDecimal("2.0")) >= 0) return "CRITICAL";
        if (ratio.compareTo(new BigDecimal("1.5")) >= 0) return "HIGH";
        if (ratio.compareTo(new BigDecimal("1.2")) >= 0) return "MEDIUM";
        return "LOW";
    }
}
