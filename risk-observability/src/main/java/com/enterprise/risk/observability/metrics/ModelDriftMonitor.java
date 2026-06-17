package com.enterprise.risk.observability.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RDeque;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型漂移监控
 * 监控模型AUC、KS指标，特征分布漂移检测并产生告警
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDriftMonitor {

    private static final String MODEL_PREDICTIONS_KEY = "risk:model:predictions:";
    private static final String MODEL_FEATURE_STATS_KEY = "risk:model:feature_stats:";
    private static final String MODEL_PERFORMANCE_KEY = "risk:model:performance:";
    private static final String MODEL_DRIFT_ALERTS_KEY = "risk:model:drift_alerts";

    private static final int MAX_PREDICTIONS_SAMPLE = 10000;
    private static final double AUC_DRIFT_THRESHOLD = 0.05;
    private static final double KS_DRIFT_THRESHOLD = 0.05;
    private static final double FEATURE_PVALUE_THRESHOLD = 0.05;
    private static final double PSI_WARNING_THRESHOLD = 0.1;
    private static final double PSI_ALERT_THRESHOLD = 0.25;

    private final RedissonClient redissonClient;
    private final MetricsCollector metricsCollector;

    /**
     * 模型基线性能缓存（训练时的基准）
     */
    private final Map<String, ModelPerformance> baselinePerformanceCache = new ConcurrentHashMap<>();

    /**
     * 特征基线分布缓存
     */
    private final Map<String, Map<String, FeatureDistribution>> baselineFeatureCache = new ConcurrentHashMap<>();

    /**
     * 记录模型预测结果
     */
    public void recordPrediction(String modelId, String modelVersion, double predictionScore,
                                 boolean actualLabel, Map<String, Object> features) {
        String key = MODEL_PREDICTIONS_KEY + modelId + ":" + modelVersion;
        RDeque<PredictionRecord> deque = redissonClient.getDeque(key);

        PredictionRecord record = PredictionRecord.builder()
                .modelId(modelId)
                .modelVersion(modelVersion)
                .score(predictionScore)
                .actualLabel(actualLabel)
                .timestamp(System.currentTimeMillis())
                .build();

        deque.addFirst(record);
        while (deque.size() > MAX_PREDICTIONS_SAMPLE) {
            deque.removeLast();
        }

        if (features != null && !features.isEmpty()) {
            updateFeatureStats(modelId, modelVersion, features);
        }

        metricsCollector.recordModelScore(predictionScore);
    }

    /**
     * 设置模型基线性能
     */
    public void setBaselinePerformance(String modelId, String modelVersion, ModelPerformance baseline) {
        String key = modelId + ":" + modelVersion;
        baselinePerformanceCache.put(key, baseline);
        redissonClient.getMap(MODEL_PERFORMANCE_KEY + "baseline").put(key, baseline);
    }

    /**
     * 设置特征基线分布
     */
    public void setBaselineFeatureDistribution(String modelId, String modelVersion,
                                                Map<String, FeatureDistribution> featureDistributions) {
        String key = modelId + ":" + modelVersion;
        baselineFeatureCache.put(key, featureDistributions);
        redissonClient.getMap(MODEL_FEATURE_STATS_KEY + "baseline").put(key, featureDistributions);
    }

    /**
     * 计算指定模型的当前性能指标
     */
    public ModelPerformance calculatePerformance(String modelId, String modelVersion) {
        String key = MODEL_PREDICTIONS_KEY + modelId + ":" + modelVersion;
        RDeque<PredictionRecord> deque = redissonClient.getDeque(key);

        if (deque.isEmpty()) {
            return ModelPerformance.builder()
                    .modelId(modelId)
                    .modelVersion(modelVersion)
                    .sampleCount(0)
                    .auc(0.0)
                    .ks(0.0)
                    .build();
        }

        List<PredictionRecord> samples = new ArrayList<>(deque);
        double auc = calculateAUC(samples);
        double ks = calculateKS(samples);
        double accuracy = calculateAccuracy(samples);
        double precision = calculatePrecision(samples);
        double recall = calculateRecall(samples);

        ModelPerformance performance = ModelPerformance.builder()
                .modelId(modelId)
                .modelVersion(modelVersion)
                .sampleCount(samples.size())
                .auc(auc)
                .ks(ks)
                .accuracy(accuracy)
                .precision(precision)
                .recall(recall)
                .calculatedAt(System.currentTimeMillis())
                .build();

        redissonClient.getMap(MODEL_PERFORMANCE_KEY + "current").put(modelId + ":" + modelVersion, performance);
        return performance;
    }

    /**
     * 检测模型性能漂移
     */
    public DriftDetectionResult detectPerformanceDrift(String modelId, String modelVersion) {
        String key = modelId + ":" + modelVersion;
        ModelPerformance baseline = baselinePerformanceCache.get(key);
        ModelPerformance current = calculatePerformance(modelId, modelVersion);

        DriftDetectionResult result = DriftDetectionResult.builder()
                .modelId(modelId)
                .modelVersion(modelVersion)
                .checkedAt(System.currentTimeMillis())
                .baselinePerformance(baseline)
                .currentPerformance(current)
                .build();

        if (baseline == null) {
            result.setDriftLevel(DriftLevel.NO_BASELINE);
            return result;
        }

        double aucDelta = baseline.getAuc() - current.getAuc();
        double ksDelta = baseline.getKs() - current.getKs();

        result.setAucDelta(aucDelta);
        result.setKsDelta(ksDelta);

        if (aucDelta > AUC_DRIFT_THRESHOLD || ksDelta > KS_DRIFT_THRESHOLD) {
            if (aucDelta > AUC_DRIFT_THRESHOLD * 2 || ksDelta > KS_DRIFT_THRESHOLD * 2) {
                result.setDriftLevel(DriftLevel.SEVERE);
                alertDrift(modelId, modelVersion, DriftLevel.SEVERE,
                        "模型性能严重漂移: AUC下降=" + String.format("%.4f", aucDelta) +
                                ", KS下降=" + String.format("%.4f", ksDelta));
            } else {
                result.setDriftLevel(DriftLevel.WARNING);
                alertDrift(modelId, modelVersion, DriftLevel.WARNING,
                        "模型性能漂移警告: AUC下降=" + String.format("%.4f", aucDelta) +
                                ", KS下降=" + String.format("%.4f", ksDelta));
            }
        } else {
            result.setDriftLevel(DriftLevel.NORMAL);
        }

        return result;
    }

    /**
     * 检测特征分布漂移（PSI方法）
     */
    public Map<String, Double> detectFeatureDrift(String modelId, String modelVersion) {
        String key = modelId + ":" + modelVersion;
        Map<String, FeatureDistribution> baseline = baselineFeatureCache.get(key);

        Map<String, Double> psiResults = new HashMap<>();
        if (baseline == null) {
            return psiResults;
        }

        @SuppressWarnings("unchecked")
        Map<String, FeatureDistribution> current = (Map<String, FeatureDistribution>)
                redissonClient.getMap(MODEL_FEATURE_STATS_KEY + "current").get(key);

        if (current == null) {
            return psiResults;
        }

        for (Map.Entry<String, FeatureDistribution> entry : baseline.entrySet()) {
            String featureName = entry.getKey();
            FeatureDistribution baseDist = entry.getValue();
            FeatureDistribution currDist = current.get(featureName);

            if (currDist != null) {
                double psi = calculatePSI(baseDist, currDist);
                psiResults.put(featureName, psi);

                if (psi >= PSI_ALERT_THRESHOLD) {
                    alertDrift(modelId, modelVersion, DriftLevel.SEVERE,
                            "特征严重漂移: " + featureName + ", PSI=" + String.format("%.4f", psi));
                } else if (psi >= PSI_WARNING_THRESHOLD) {
                    alertDrift(modelId, modelVersion, DriftLevel.WARNING,
                            "特征漂移警告: " + featureName + ", PSI=" + String.format("%.4f", psi));
                }
            }
        }

        return psiResults;
    }

    /**
     * 定时执行模型漂移检测（每小时）
     */
    @Scheduled(cron = "0 0 * * * *")
    public void scheduledDriftDetection() {
        log.info("[ModelDriftMonitor] 开始定时模型漂移检测...");
        for (String key : baselinePerformanceCache.keySet()) {
            try {
                String[] parts = key.split(":");
                if (parts.length >= 2) {
                    detectPerformanceDrift(parts[0], parts[1]);
                    detectFeatureDrift(parts[0], parts[1]);
                }
            } catch (Exception e) {
                log.error("[ModelDriftMonitor] 模型漂移检测异常: key={}", key, e);
            }
        }
        log.info("[ModelDriftMonitor] 定时模型漂移检测完成");
    }

    private double calculateAUC(List<PredictionRecord> samples) {
        if (samples.isEmpty()) return 0.0;

        List<PredictionRecord> sorted = new ArrayList<>(samples);
        sorted.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        long positives = sorted.stream().filter(PredictionRecord::isActualLabel).count();
        long negatives = sorted.size() - positives;

        if (positives == 0 || negatives == 0) return 0.5;

        long tp = 0, fp = 0;
        long prevTp = 0, prevFp = 0;
        double auc = 0.0;

        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).isActualLabel()) tp++;
            else fp++;

            if (i == sorted.size() - 1 ||
                    Double.compare(sorted.get(i).getScore(), sorted.get(i + 1).getScore()) != 0) {
                auc += (tp - prevTp) * (fp + prevFp) / 2.0;
                prevTp = tp;
                prevFp = fp;
            }
        }

        return auc / (positives * negatives);
    }

    private double calculateKS(List<PredictionRecord> samples) {
        if (samples.isEmpty()) return 0.0;

        List<PredictionRecord> sorted = new ArrayList<>(samples);
        sorted.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        long totalPositives = sorted.stream().filter(PredictionRecord::isActualLabel).count();
        long totalNegatives = sorted.size() - totalPositives;

        if (totalPositives == 0 || totalNegatives == 0) return 0.0;

        long cumPos = 0, cumNeg = 0;
        double maxDiff = 0.0;

        for (PredictionRecord r : sorted) {
            if (r.isActualLabel()) cumPos++;
            else cumNeg++;

            double tpr = (double) cumPos / totalPositives;
            double fpr = (double) cumNeg / totalNegatives;
            maxDiff = Math.max(maxDiff, Math.abs(tpr - fpr));
        }

        return maxDiff;
    }

    private double calculateAccuracy(List<PredictionRecord> samples) {
        if (samples.isEmpty()) return 0.0;
        long correct = samples.stream()
                .filter(s -> (s.getScore() >= 0.5) == s.isActualLabel())
                .count();
        return (double) correct / samples.size();
    }

    private double calculatePrecision(List<PredictionRecord> samples) {
        long predictedPos = samples.stream().filter(s -> s.getScore() >= 0.5).count();
        if (predictedPos == 0) return 0.0;
        long truePos = samples.stream()
                .filter(s -> s.getScore() >= 0.5 && s.isActualLabel())
                .count();
        return (double) truePos / predictedPos;
    }

    private double calculateRecall(List<PredictionRecord> samples) {
        long actualPos = samples.stream().filter(PredictionRecord::isActualLabel).count();
        if (actualPos == 0) return 0.0;
        long truePos = samples.stream()
                .filter(s -> s.getScore() >= 0.5 && s.isActualLabel())
                .count();
        return (double) truePos / actualPos;
    }

    private double calculatePSI(FeatureDistribution baseline, FeatureDistribution current) {
        double psi = 0.0;
        Map<Double, Double> baseBins = baseline.getBinnedDistribution();
        Map<Double, Double> currBins = current.getBinnedDistribution();

        for (Map.Entry<Double, Double> entry : baseBins.entrySet()) {
            double bin = entry.getKey();
            double expectedPct = entry.getValue() + 1e-10;
            double actualPct = currBins.getOrDefault(bin, 0.0001);

            if (actualPct <= 0) actualPct = 0.0001;
            psi += (actualPct - expectedPct) * Math.log(actualPct / expectedPct);
        }

        return psi;
    }

    @SuppressWarnings("unchecked")
    private void updateFeatureStats(String modelId, String modelVersion, Map<String, Object> features) {
        String key = modelId + ":" + modelVersion;
        Map<String, FeatureDistribution> currentDistributions = (Map<String, FeatureDistribution>)
                redissonClient.getMap(MODEL_FEATURE_STATS_KEY + "current").get(key);

        if (currentDistributions == null) {
            currentDistributions = new HashMap<>();
        }

        for (Map.Entry<String, Object> feature : features.entrySet()) {
            String name = feature.getKey();
            Object value = feature.getValue();

            if (value instanceof Number) {
                double numVal = ((Number) value).doubleValue();
                FeatureDistribution fd = currentDistributions.computeIfAbsent(name,
                        k -> new FeatureDistribution(name, new HashMap<>()));
                fd.addSample(numVal);
            }
        }

        redissonClient.getMap(MODEL_FEATURE_STATS_KEY + "current").put(key, currentDistributions);
    }

    private void alertDrift(String modelId, String modelVersion, DriftLevel level, String message) {
        log.warn("[ModelDriftMonitor] 模型漂移告警 - level={}, model={}:{}, message={}",
                level, modelId, modelVersion, message);

        DriftAlert alert = DriftAlert.builder()
                .alertId("DRIFT-" + System.currentTimeMillis())
                .modelId(modelId)
                .modelVersion(modelVersion)
                .level(level)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();

        RDeque<DriftAlert> alerts = redissonClient.getDeque(MODEL_DRIFT_ALERTS_KEY);
        alerts.addFirst(alert);
        while (alerts.size() > 1000) {
            alerts.removeLast();
        }
    }

    public enum DriftLevel {
        NORMAL, WARNING, SEVERE, NO_BASELINE
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PredictionRecord implements Serializable {
        private String modelId;
        private String modelVersion;
        private double score;
        private boolean actualLabel;
        private long timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelPerformance implements Serializable {
        private String modelId;
        private String modelVersion;
        private int sampleCount;
        private Double auc;
        private Double ks;
        private Double accuracy;
        private Double precision;
        private Double recall;
        private Long calculatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeatureDistribution implements Serializable {
        private String featureName;
        private Map<Double, Double> binnedDistribution;
        private Double mean;
        private Double std;
        private Long sampleCount;

        public FeatureDistribution(String featureName, Map<Double, Double> binnedDistribution) {
            this.featureName = featureName;
            this.binnedDistribution = binnedDistribution;
            this.sampleCount = 0L;
        }

        public void addSample(double value) {
            this.sampleCount = (this.sampleCount == null ? 0 : this.sampleCount) + 1;
            double bin = Math.floor(value * 10) / 10.0;
            binnedDistribution.merge(bin, 1.0, Double::sum);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DriftDetectionResult implements Serializable {
        private String modelId;
        private String modelVersion;
        private DriftLevel driftLevel;
        private Double aucDelta;
        private Double ksDelta;
        private ModelPerformance baselinePerformance;
        private ModelPerformance currentPerformance;
        private Long checkedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DriftAlert implements Serializable {
        private String alertId;
        private String modelId;
        private String modelVersion;
        private DriftLevel level;
        private String message;
        private long timestamp;
    }
}
