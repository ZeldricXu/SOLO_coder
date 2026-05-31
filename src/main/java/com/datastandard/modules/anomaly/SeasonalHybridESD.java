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
import java.util.*;

@Slf4j
@Component
public class SeasonalHybridESD implements DetectionAlgorithm {

    private static final String ALGORITHM_NAME = "Seasonal Hybrid ESD";
    private static final String ALGORITHM_TYPE = "SEASONAL_HSD";
    private static final int DEFAULT_PERIOD = 7;
    private static final int DEFAULT_MAX_ANOMALIES = 10;
    private static final BigDecimal DEFAULT_SIGNIFICANCE = new BigDecimal("0.05");
    private static final int DEFAULT_ROBUST_ITERS = 3;

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
                        log.warn("数据点不足，跳过Seasonal HSD检测: metricCode={}, count={}",
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
        List<BigDecimal> values = dataPoints.stream()
                .map(AnomalyDetectionRequest.DataPoint::getValue)
                .toList();

        AlgorithmConfig.SeasonalHsdConfig shsdConfig = config.getSeasonalHsdConfig();
        int period = shsdConfig != null && shsdConfig.getPeriod() != null
                ? shsdConfig.getPeriod() : DEFAULT_PERIOD;
        int maxAnomalies = shsdConfig != null && shsdConfig.getMaxAnomalies() != null
                ? shsdConfig.getMaxAnomalies() : DEFAULT_MAX_ANOMALIES;
        BigDecimal significance = shsdConfig != null && shsdConfig.getSignificanceLevel() != null
                ? shsdConfig.getSignificanceLevel() : DEFAULT_SIGNIFICANCE;
        int robustIters = shsdConfig != null && shsdConfig.getRobustIters() != null
                ? shsdConfig.getRobustIters() : DEFAULT_ROBUST_ITERS;
        boolean useAutoCorrelation = shsdConfig != null && Boolean.TRUE.equals(shsdConfig.getUseAutoCorrelation());

        period = Math.min(period, values.size() / 3);

        List<BigDecimal> seasonal = extractSeasonalComponent(values, period);
        List<BigDecimal> trend = extractTrendComponent(values, robustIters);
        List<BigDecimal> residual = calculateResidual(values, seasonal, trend);

        if (useAutoCorrelation) {
            period = detectPeriodByAutoCorrelation(values, Math.min(period * 2, values.size() / 2));
            seasonal = extractSeasonalComponent(values, period);
            residual = calculateResidual(values, seasonal, trend);
        }

        Set<Integer> anomalyIndices = performESDTest(residual, maxAnomalies, significance);

        for (Integer idx : anomalyIndices) {
            if (idx < 0 || idx >= dataPoints.size()) continue;

            AnomalyDetectionRequest.DataPoint dataPoint = dataPoints.get(idx);
            BigDecimal residualValue = residual.get(idx).abs();
            BigDecimal threshold = calculateESDThreshold(residual, significance);
            BigDecimal anomalyScore = residualValue.divide(threshold, 4, RoundingMode.HALF_UP)
                    .min(new BigDecimal("1.0"));
            BigDecimal confidence = calculateConfidence(residualValue, threshold);
            String severity = determineSeverity(residualValue, threshold);
            String anomalyType = dataPoint.getValue().compareTo(trend.get(idx)) > 0 ? "SPIKE" : "DIP";

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
                    .threshold(threshold)
                    .expectedValue(trend.get(idx).add(seasonal.get(idx % period)))
                    .actualValue(dataPoint.getValue())
                    .detectedAt(LocalDateTime.now())
                    .windowStart(request.getWindowStart())
                    .windowEnd(request.getWindowEnd())
                    .algorithmType(ALGORITHM_TYPE)
                    .anomalyData(Map.of(
                            "residual", residual.get(idx),
                            "seasonal", seasonal.get(idx % period),
                            "trend", trend.get(idx),
                            "period", period,
                            "maxAnomalies", maxAnomalies,
                            "significance", significance,
                            "dataPointIndex", idx
                    ))
                    .description(String.format("Seasonal HSD检测到异常: 残差=%.4f, 阈值=%.4f, 周期=%d",
                            residual.get(idx), threshold, period))
                    .suggestedAction("检查季节性模式变化，考虑调整周期参数")
                    .build();
            results.add(result);
        }

        log.info("Seasonal HSD检测完成: metricCode={}, 异常数={}, 数据点总数={}, 周期={}",
                request.getMetricCode(), results.size(), values.size(), period);
        return results;
    }

    @Override
    public Mono<BigDecimal> calculateAnomalyScore(List<BigDecimal> data, BigDecimal value, AlgorithmConfig config) {
        return Mono.fromCallable(() -> {
            if (data.size() < 10) return BigDecimal.ZERO;

            AlgorithmConfig.SeasonalHsdConfig shsdConfig = config.getSeasonalHsdConfig();
            int period = shsdConfig != null && shsdConfig.getPeriod() != null
                    ? shsdConfig.getPeriod() : DEFAULT_PERIOD;
            period = Math.min(period, data.size() / 3);

            List<BigDecimal> seasonal = extractSeasonalComponent(data, period);
            List<BigDecimal> trend = extractTrendComponent(data, DEFAULT_ROBUST_ITERS);
            List<BigDecimal> residual = calculateResidual(data, seasonal, trend);

            BigDecimal threshold = calculateESDThreshold(residual, DEFAULT_SIGNIFICANCE);
            int lastIdx = data.size() - 1;
            BigDecimal expectedValue = trend.get(lastIdx).add(seasonal.get(lastIdx % period));
            BigDecimal residualValue = value.subtract(expectedValue).abs();

            return residualValue.divide(threshold, 4, RoundingMode.HALF_UP).min(new BigDecimal("1.0"));
        });
    }

    private List<BigDecimal> extractSeasonalComponent(List<BigDecimal> values, int period) {
        List<BigDecimal> seasonal = new ArrayList<>(period);
        for (int i = 0; i < period; i++) {
            List<BigDecimal> group = new ArrayList<>();
            for (int j = i; j < values.size(); j += period) {
                group.add(values.get(j));
            }
            seasonal.add(calculateMedian(group));
        }
        BigDecimal seasonalMean = calculateMean(seasonal);
        return seasonal.stream()
                .map(v -> v.subtract(seasonalMean))
                .toList();
    }

    private List<BigDecimal> extractTrendComponent(List<BigDecimal> values, int robustIters) {
        List<BigDecimal> trend = new ArrayList<>(values.size());
        int window = 7;

        for (int i = 0; i < values.size(); i++) {
            int start = Math.max(0, i - window / 2);
            int end = Math.min(values.size(), i + window / 2 + 1);
            List<BigDecimal> windowValues = new ArrayList<>(values.subList(start, end));

            BigDecimal median = calculateMedian(windowValues);

            for (int iter = 0; iter < robustIters && windowValues.size() > 3; iter++) {
                BigDecimal finalMedian = median;
                List<BigDecimal> absDev = windowValues.stream()
                        .map(v -> v.subtract(finalMedian).abs())
                        .sorted()
                        .toList();
                BigDecimal mad = calculateMedian(absDev).multiply(new BigDecimal("1.4826"));
                if (mad.compareTo(BigDecimal.ZERO) == 0) break;

                BigDecimal threshold = mad.multiply(new BigDecimal("3"));
                List<BigDecimal> filtered = new ArrayList<>();
                for (BigDecimal v : windowValues) {
                    if (v.subtract(median).abs().compareTo(threshold) <= 0) {
                        filtered.add(v);
                    }
                }
                if (filtered.size() < 3) break;
                windowValues = filtered;
                median = calculateMedian(windowValues);
            }

            trend.add(median);
        }

        return trend;
    }

    private List<BigDecimal> calculateResidual(List<BigDecimal> values, List<BigDecimal> seasonal, List<BigDecimal> trend) {
        List<BigDecimal> residual = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            BigDecimal s = seasonal.get(i % seasonal.size());
            BigDecimal t = trend.get(i);
            residual.add(values.get(i).subtract(s).subtract(t));
        }
        return residual;
    }

    private int detectPeriodByAutoCorrelation(List<BigDecimal> values, int maxLag) {
        maxLag = Math.min(maxLag, values.size() / 2);
        if (maxLag < 2) return DEFAULT_PERIOD;

        BigDecimal mean = calculateMean(values);
        BigDecimal variance = calculateVariance(values, mean);
        if (variance.compareTo(BigDecimal.ZERO) == 0) return DEFAULT_PERIOD;

        List<BigDecimal> acf = new ArrayList<>();
        for (int lag = 1; lag <= maxLag; lag++) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = lag; i < values.size(); i++) {
                sum = sum.add(values.get(i).subtract(mean)
                        .multiply(values.get(i - lag).subtract(mean)));
            }
            BigDecimal autocorr = sum.divide(BigDecimal.valueOf(values.size() - lag), 6, RoundingMode.HALF_UP)
                    .divide(variance, 6, RoundingMode.HALF_UP);
            acf.add(autocorr);
        }

        int bestPeriod = DEFAULT_PERIOD;
        BigDecimal maxCorr = BigDecimal.ZERO;
        for (int i = 0; i < acf.size(); i++) {
            if (acf.get(i).compareTo(maxCorr) > 0 && acf.get(i).compareTo(new BigDecimal("0.3")) > 0) {
                maxCorr = acf.get(i);
                bestPeriod = i + 1;
            }
        }

        return bestPeriod;
    }

    private Set<Integer> performESDTest(List<BigDecimal> residual, int maxAnomalies, BigDecimal significance) {
        Set<Integer> anomalies = new HashSet<>();
        List<BigDecimal> residuals = new ArrayList<>(residual);
        Map<Integer, Integer> originalIndices = new HashMap<>();
        for (int i = 0; i < residuals.size(); i++) {
            originalIndices.put(i, i);
        }

        int n = residuals.size();
        maxAnomalies = Math.min(maxAnomalies, n / 4);

        for (int k = 1; k <= maxAnomalies; k++) {
            BigDecimal mean = calculateMean(residuals);
            BigDecimal std = calculateStdDev(residuals, mean);

            if (std.compareTo(BigDecimal.ZERO) == 0) break;

            int maxIdx = 0;
            BigDecimal maxAbs = BigDecimal.ZERO;
            for (int i = 0; i < residuals.size(); i++) {
                BigDecimal abs = residuals.get(i).subtract(mean).abs();
                if (abs.compareTo(maxAbs) > 0) {
                    maxAbs = abs;
                    maxIdx = i;
                }
            }

            BigDecimal testStat = maxAbs.divide(std, 6, RoundingMode.HALF_UP);
            double criticalValue = calculateCriticalValue(n - k + 1, significance);

            if (testStat.doubleValue() > criticalValue) {
                anomalies.add(originalIndices.get(maxIdx));
                residuals.remove(maxIdx);

                for (int i = maxIdx; i < residuals.size(); i++) {
                    originalIndices.put(i, originalIndices.getOrDefault(i + 1, i + 1));
                }
            } else {
                break;
            }
        }

        return anomalies;
    }

    private BigDecimal calculateESDThreshold(List<BigDecimal> residual, BigDecimal significance) {
        BigDecimal mean = calculateMean(residual);
        BigDecimal std = calculateStdDev(residual, mean);
        double cv = calculateCriticalValue(residual.size(), significance);
        return std.multiply(BigDecimal.valueOf(cv));
    }

    private double calculateCriticalValue(int n, BigDecimal significance) {
        double alpha = significance.doubleValue() / 2;
        double t = 1 - alpha / n;
        double df = n - 2;

        if (df <= 0) return 3.0;

        return inverseT(t, df);
    }

    private double inverseT(double p, double df) {
        if (p <= 0.5) return 0.0;
        if (df == 1) return Math.tan(Math.PI * (p - 0.5));

        double z = inverseNormal(p);
        double h = z * (1 + 0.25 / df) * (1 + (z * z + 3) / (4 * df) * (1 + (z * z + 1) / (2 * df)));
        return Math.abs(h) < 1e-10 ? z : h;
    }

    private double inverseNormal(double p) {
        if (p <= 0) return Double.NEGATIVE_INFINITY;
        if (p >= 1) return Double.POSITIVE_INFINITY;
        if (p == 0.5) return 0;

        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02,
                -2.759285104469687e+02, 1.383577518672690e+02,
                -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02,
                -1.556989798598866e+02, 6.680131188771972e+01,
                -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01,
                -2.400758277161838e+00, -2.549732539343734e+00,
                4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01,
                2.445134137142996e+00, 3.754408661907416e+00};

        double p_low = 0.02425;
        double p_high = 1 - p_low;
        double q, r;

        if (p < p_low) {
            q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        } else if (p <= p_high) {
            q = p - 0.5;
            r = q * q;
            return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                    (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
        } else {
            q = Math.sqrt(-2 * Math.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
    }

    private BigDecimal calculateMean(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateVariance(List<BigDecimal> values, BigDecimal mean) {
        if (values.size() < 2) return BigDecimal.ZERO;
        BigDecimal sumSquaredDiff = values.stream()
                .map(v -> v.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sumSquaredDiff.divide(BigDecimal.valueOf(values.size() - 1), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateStdDev(List<BigDecimal> values, BigDecimal mean) {
        BigDecimal variance = calculateVariance(values, mean);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
    }

    private BigDecimal calculateMedian(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int size = sorted.size();
        if (size % 2 == 0) {
            return sorted.get(size / 2 - 1).add(sorted.get(size / 2))
                    .divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
        } else {
            return sorted.get(size / 2);
        }
    }

    private BigDecimal calculateConfidence(BigDecimal residual, BigDecimal threshold) {
        BigDecimal ratio = residual.divide(threshold, 4, RoundingMode.HALF_UP);
        BigDecimal confidence = ratio.multiply(new BigDecimal("0.5")).add(new BigDecimal("0.5"));
        return confidence.min(new BigDecimal("0.99"));
    }

    private String determineSeverity(BigDecimal residual, BigDecimal threshold) {
        BigDecimal ratio = residual.divide(threshold, 2, RoundingMode.HALF_UP);
        if (ratio.compareTo(new BigDecimal("2.0")) >= 0) return "CRITICAL";
        if (ratio.compareTo(new BigDecimal("1.5")) >= 0) return "HIGH";
        if (ratio.compareTo(new BigDecimal("1.2")) >= 0) return "MEDIUM";
        return "LOW";
    }
}
