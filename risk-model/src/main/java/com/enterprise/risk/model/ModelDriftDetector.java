package com.enterprise.risk.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ModelDriftDetector {

    private final Map<String, FeatureStatistics> statisticsStore = new ConcurrentHashMap<>();

    private final Map<String, FeatureBaseline> baselineStore = new ConcurrentHashMap<>();

    private static final int DEFAULT_MAX_SAMPLES = 10000;

    private static final long DEFAULT_WINDOW_MS = 3600000L;

    private static final double DEFAULT_DRIFT_THRESHOLD = 2.0;

    public void recordFeatures(String modelId, float[] features) {
        if (features == null || features.length == 0) {
            return;
        }

        statisticsStore.compute(modelId, (k, v) -> {
            FeatureStatistics stats = v;
            if (stats == null) {
                stats = FeatureStatistics.builder()
                        .modelId(modelId)
                        .featureDim(features.length)
                        .maxSamples(DEFAULT_MAX_SAMPLES)
                        .windowMs(DEFAULT_WINDOW_MS)
                        .build();
            }
            stats.addSample(features);
            return stats;
        });
    }

    public void setBaseline(String modelId, FeatureBaseline baseline) {
        if (baseline == null) {
            return;
        }
        baselineStore.put(modelId, baseline);
        log.info("已设置模型 [{}] 的特征基线，维度: {}", modelId, baseline.getFeatureDim());
    }

    public void setBaseline(String modelId, float[][] baselineSamples) {
        if (baselineSamples == null || baselineSamples.length == 0) {
            return;
        }

        int dim = baselineSamples[0].length;
        double[] means = new double[dim];
        double[] variances = new double[dim];

        for (float[] sample : baselineSamples) {
            for (int i = 0; i < dim; i++) {
                means[i] += sample[i];
            }
        }
        for (int i = 0; i < dim; i++) {
            means[i] /= baselineSamples.length;
        }

        for (float[] sample : baselineSamples) {
            for (int i = 0; i < dim; i++) {
                double diff = sample[i] - means[i];
                variances[i] += diff * diff;
            }
        }
        for (int i = 0; i < dim; i++) {
            variances[i] /= baselineSamples.length;
        }

        FeatureBaseline baseline = FeatureBaseline.builder()
                .modelId(modelId)
                .featureDim(dim)
                .means(means)
                .variances(variances)
                .sampleCount(baselineSamples.length)
                .createdAt(Instant.now().toEpochMilli())
                .build();

        setBaseline(modelId, baseline);
    }

    public DriftReport detectDrift(String modelId) {
        FeatureStatistics stats = statisticsStore.get(modelId);
        FeatureBaseline baseline = baselineStore.get(modelId);

        DriftReport report = DriftReport.builder()
                .modelId(modelId)
                .detectedAt(Instant.now().toEpochMilli())
                .hasBaseline(baseline != null)
                .build();

        if (stats == null) {
            report.setMessage("暂无统计数据，无法检测漂移");
            return report;
        }

        report.setCurrentSampleCount(stats.getSampleCount());
        report.setCurrentMeans(stats.getCurrentMeans());
        report.setCurrentVariances(stats.getCurrentVariances());

        if (baseline == null) {
            report.setDrifted(false);
            report.setMessage("未设置基线数据，仅输出当前统计");
            return report;
        }

        computeDriftMetrics(report, stats, baseline);

        return report;
    }

    private void computeDriftMetrics(DriftReport report,
                                     FeatureStatistics stats,
                                     FeatureBaseline baseline) {
        int dim = baseline.getFeatureDim();
        int currentDim = stats.getFeatureDim();

        if (dim != currentDim) {
            report.setDrifted(true);
            report.setMessage(String.format(
                    "特征维度不匹配: 基线=%d, 当前=%d", dim, currentDim));
            report.setOverallDriftScore(Double.MAX_VALUE);
            return;
        }

        double[] baselineMeans = baseline.getMeans();
        double[] baselineVars = baseline.getVariances();
        double[] currentMeans = stats.getCurrentMeans();
        double[] currentVars = stats.getCurrentVariances();

        double[] zScores = new double[dim];
        double[] ksDistances = new double[dim];
        List<Integer> driftedFeatures = new ArrayList<>();

        double totalZScore = 0.0;

        for (int i = 0; i < dim; i++) {
            double baselineStd = Math.sqrt(Math.max(baselineVars[i], 1e-10));
            double zScore = Math.abs(currentMeans[i] - baselineMeans[i]) / baselineStd;
            zScores[i] = zScore;
            totalZScore += zScore;

            double ksDist = computeKSDistance(
                    baselineMeans[i], baselineStd,
                    currentMeans[i], Math.sqrt(Math.max(currentVars[i], 1e-10)));
            ksDistances[i] = ksDist;

            if (zScore > DEFAULT_DRIFT_THRESHOLD || ksDist > 0.2) {
                driftedFeatures.add(i);
            }
        }

        report.setZScores(zScores);
        report.setKsDistances(ksDistances);
        report.setDriftedFeatureIndices(driftedFeatures);

        double avgZScore = totalZScore / dim;
        report.setOverallDriftScore(avgZScore);
        report.setDrifted(!driftedFeatures.isEmpty() || avgZScore > DEFAULT_DRIFT_THRESHOLD);

        if (report.isDrifted()) {
            report.setMessage(String.format(
                    "检测到特征漂移: %d个特征异常，平均Z-Score=%.4f",
                    driftedFeatures.size(), avgZScore));
        } else {
            report.setMessage(String.format(
                    "未检测到显著漂移，平均Z-Score=%.4f", avgZScore));
        }
    }

    private double computeKSDistance(double mean1, double std1,
                                     double mean2, double std2) {
        double diff = Math.abs(mean1 - mean2);
        double avgStd = (std1 + std2) / 2.0;
        if (avgStd < 1e-10) {
            return diff > 1e-6 ? 1.0 : 0.0;
        }
        return Math.min(1.0, diff / (avgStd * 2.0));
    }

    public FeatureStatistics getStatistics(String modelId) {
        return statisticsStore.get(modelId);
    }

    public FeatureBaseline getBaseline(String modelId) {
        return baselineStore.get(modelId);
    }

    public void clearStatistics(String modelId) {
        statisticsStore.remove(modelId);
        log.info("已清除模型 [{}] 的漂移统计数据", modelId);
    }

    public void clearAllStatistics() {
        int count = statisticsStore.size();
        statisticsStore.clear();
        log.info("已清除所有模型的漂移统计数据，共 {} 个", count);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeatureBaseline implements Serializable {
        private String modelId;
        private int featureDim;
        private double[] means;
        private double[] variances;
        private int sampleCount;
        private long createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeatureStatistics implements Serializable {
        private String modelId;
        private int featureDim;

        @Builder.Default
        private int maxSamples = DEFAULT_MAX_SAMPLES;

        @Builder.Default
        private long windowMs = DEFAULT_WINDOW_MS;

        @Builder.Default
        private List<float[]> samples = new ArrayList<>();

        @Builder.Default
        private int sampleCount = 0;

        @Builder.Default
        private Long windowStart = null;

        public synchronized void addSample(float[] features) {
            long now = Instant.now().toEpochMilli();

            if (windowStart == null || now - windowStart > windowMs) {
                windowStart = now;
                samples.clear();
                sampleCount = 0;
            }

            if (samples.size() >= maxSamples) {
                samples.remove(0);
            } else {
                sampleCount++;
            }
            samples.add(features.clone());
        }

        public synchronized double[] getCurrentMeans() {
            if (samples.isEmpty()) {
                return new double[featureDim];
            }

            double[] means = new double[featureDim];
            for (float[] sample : samples) {
                for (int i = 0; i < featureDim; i++) {
                    means[i] += sample[i];
                }
            }
            int n = samples.size();
            for (int i = 0; i < featureDim; i++) {
                means[i] /= n;
            }
            return means;
        }

        public synchronized double[] getCurrentVariances() {
            if (samples.isEmpty()) {
                return new double[featureDim];
            }

            double[] means = getCurrentMeans();
            double[] variances = new double[featureDim];
            int n = samples.size();

            for (float[] sample : samples) {
                for (int i = 0; i < featureDim; i++) {
                    double diff = sample[i] - means[i];
                    variances[i] += diff * diff;
                }
            }
            for (int i = 0; i < featureDim; i++) {
                variances[i] /= Math.max(1, n - 1);
            }
            return variances;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DriftReport implements Serializable {
        private String modelId;
        private long detectedAt;
        private boolean hasBaseline;
        private boolean drifted;
        private String message;
        private int currentSampleCount;
        private double[] currentMeans;
        private double[] currentVariances;
        private double[] zScores;
        private double[] ksDistances;
        private List<Integer> driftedFeatureIndices;
        private double overallDriftScore;
    }
}
