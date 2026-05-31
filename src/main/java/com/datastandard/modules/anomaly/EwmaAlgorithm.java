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
public class EwmaAlgorithm implements DetectionAlgorithm {

    private static final String ALGORITHM_NAME = "EWMA";
    private static final String ALGORITHM_TYPE = "EWMA";
    private static final BigDecimal DEFAULT_ALPHA = new BigDecimal("0.2");
    private static final BigDecimal DEFAULT_BETA = new BigDecimal("0.1");
    private static final BigDecimal DEFAULT_CONTROL_LIMIT = new BigDecimal("3.0");
    private static final int DEFAULT_WARMUP_PERIOD = 10;

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
                        log.warn("数据点不足，跳过EWMA检测: metricCode={}, count={}",
                                request.getMetricCode(), request.getDataPoints().size());
                        return Mono.just(new ArrayList<AnomalyResult>());
                    }
                    return Mono.fromCallable(() -> performDetection(request, config))
                            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
                });
    }

    private List<AnomalyResult> performDetection(AnomalyDetectionRequest request, AlgorithmConfig config) {
        List<AnomalyResult> results = new ArrayList<>();
        List<AnomalyDetectionRequest.DataPoint> dataPoints = request.getDataPoints();

        AlgorithmConfig.EwmaConfig ewmaConfig = config.getEwmaConfig();
        BigDecimal alpha = ewmaConfig != null && ewmaConfig.getAlpha() != null
                ? ewmaConfig.getAlpha() : DEFAULT_ALPHA;
        BigDecimal beta = ewmaConfig != null && ewmaConfig.getBeta() != null
                ? ewmaConfig.getBeta() : DEFAULT_BETA;
        BigDecimal controlLimitMultiplier = ewmaConfig != null && ewmaConfig.getControlLimitMultiplier() != null
                ? ewmaConfig.getControlLimitMultiplier() : DEFAULT_CONTROL_LIMIT;
        int warmupPeriod = ewmaConfig != null && ewmaConfig.getWarmupPeriod() != null
                ? ewmaConfig.getWarmupPeriod() : DEFAULT_WARMUP_PERIOD;

        BigDecimal mean = calculateInitialMean(dataPoints, warmupPeriod);
        BigDecimal variance = calculateInitialVariance(dataPoints, warmupPeriod, mean);
        BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));

        BigDecimal ewma = mean;
        BigDecimal ewmaVar = variance;

        for (int i = 0; i < dataPoints.size(); i++) {
            AnomalyDetectionRequest.DataPoint dataPoint = dataPoints.get(i);
            BigDecimal value = dataPoint.getValue();

            if (i >= warmupPeriod) {
                BigDecimal controlLimit = stdDev.multiply(controlLimitMultiplier);
                BigDecimal lowerBound = ewma.subtract(controlLimit);
                BigDecimal upperBound = ewma.add(controlLimit);

                boolean isAnomaly = value.compareTo(lowerBound) < 0 || value.compareTo(upperBound) > 0;

                if (isAnomaly) {
                    BigDecimal deviation = value.subtract(ewma).abs();
                    BigDecimal anomalyScore = deviation.divide(controlLimit, 4, RoundingMode.HALF_UP)
                            .min(new BigDecimal("1.0"));
                    BigDecimal confidence = calculateConfidence(deviation, controlLimit);
                    String severity = determineSeverity(deviation, controlLimit);
                    String anomalyType = value.compareTo(ewma) > 0 ? "SPIKE" : "DIP";

                    AnomalyResult result = AnomalyResult.builder()
                            .resultId(IdGenerator.generateStrId())
                            .detectionCode(request.getDetectionCode())
                            .metricCode(request.getMetricCode())
                            .entityId(request.getEntityId())
                            .instanceId(request.getInstanceId())
                            .isAnomaly(true)
                            .anomalyType(anomalyType)
                            .severity(severity)
                            .confidence(confidence)
                            .anomalyScore(anomalyScore)
                            .threshold(controlLimit)
                            .expectedValue(ewma)
                            .actualValue(value)
                            .detectedAt(LocalDateTime.now())
                            .windowStart(request.getWindowStart())
                            .windowEnd(request.getWindowEnd())
                            .algorithmType(ALGORITHM_TYPE)
                            .anomalyData(Map.of(
                                    "ewma", ewma,
                                    "stdDev", stdDev,
                                    "upperBound", upperBound,
                                    "lowerBound", lowerBound,
                                    "alpha", alpha,
                                    "beta", beta,
                                    "dataPointIndex", i
                            ))
                            .description(String.format("EWMA检测到异常: 值=%.4f, 范围=[%.4f, %.4f]",
                                    value, lowerBound, upperBound))
                            .suggestedAction("检查趋势变化，考虑调整平滑系数alpha")
                            .build();
                    results.add(result);
                }
            }

            ewma = alpha.multiply(value).add(BigDecimal.ONE.subtract(alpha).multiply(ewma));
            BigDecimal residual = value.subtract(ewma);
            ewmaVar = beta.multiply(residual.pow(2)).add(BigDecimal.ONE.subtract(beta).multiply(ewmaVar));
            stdDev = BigDecimal.valueOf(Math.sqrt(ewmaVar.doubleValue()));
        }

        log.info("EWMA检测完成: metricCode={}, 异常数={}, 数据点总数={}",
                request.getMetricCode(), results.size(), dataPoints.size());
        return results;
    }

    @Override
    public Mono<BigDecimal> calculateAnomalyScore(List<BigDecimal> data, BigDecimal value, AlgorithmConfig config) {
        return Mono.fromCallable(() -> {
            if (data.size() < 5) return BigDecimal.ZERO;

            AlgorithmConfig.EwmaConfig ewmaConfig = config.getEwmaConfig();
            BigDecimal alpha = ewmaConfig != null && ewmaConfig.getAlpha() != null
                    ? ewmaConfig.getAlpha() : DEFAULT_ALPHA;
            BigDecimal controlLimitMultiplier = ewmaConfig != null && ewmaConfig.getControlLimitMultiplier() != null
                    ? ewmaConfig.getControlLimitMultiplier() : DEFAULT_CONTROL_LIMIT;

            BigDecimal mean = calculateInitialMeanFromValues(data);
            BigDecimal variance = calculateInitialVarianceFromValues(data, mean);
            BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));

            BigDecimal ewma = mean;
            for (BigDecimal v : data) {
                ewma = alpha.multiply(v).add(BigDecimal.ONE.subtract(alpha).multiply(ewma));
            }

            BigDecimal controlLimit = stdDev.multiply(controlLimitMultiplier);
            BigDecimal deviation = value.subtract(ewma).abs();

            return deviation.divide(controlLimit, 4, RoundingMode.HALF_UP).min(new BigDecimal("1.0"));
        });
    }

    private BigDecimal calculateInitialMean(List<AnomalyDetectionRequest.DataPoint> dataPoints, int warmupPeriod) {
        int count = Math.min(warmupPeriod, dataPoints.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < count; i++) {
            sum = sum.add(dataPoints.get(i).getValue());
        }
        return sum.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateInitialVariance(List<AnomalyDetectionRequest.DataPoint> dataPoints, int warmupPeriod, BigDecimal mean) {
        int count = Math.min(warmupPeriod, dataPoints.size());
        BigDecimal sumSquaredDiff = BigDecimal.ZERO;
        for (int i = 0; i < count; i++) {
            BigDecimal diff = dataPoints.get(i).getValue().subtract(mean);
            sumSquaredDiff = sumSquaredDiff.add(diff.pow(2));
        }
        return sumSquaredDiff.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateInitialMeanFromValues(List<BigDecimal> values) {
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateInitialVarianceFromValues(List<BigDecimal> values, BigDecimal mean) {
        BigDecimal sumSquaredDiff = values.stream()
                .map(v -> v.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sumSquaredDiff.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateConfidence(BigDecimal deviation, BigDecimal controlLimit) {
        BigDecimal ratio = deviation.divide(controlLimit, 4, RoundingMode.HALF_UP);
        BigDecimal confidence = ratio.multiply(new BigDecimal("0.5")).add(new BigDecimal("0.5"));
        return confidence.min(new BigDecimal("0.99"));
    }

    private String determineSeverity(BigDecimal deviation, BigDecimal controlLimit) {
        BigDecimal ratio = deviation.divide(controlLimit, 2, RoundingMode.HALF_UP);
        if (ratio.compareTo(new BigDecimal("2.5")) >= 0) return "CRITICAL";
        if (ratio.compareTo(new BigDecimal("1.8")) >= 0) return "HIGH";
        if (ratio.compareTo(new BigDecimal("1.3")) >= 0) return "MEDIUM";
        return "LOW";
    }
}
