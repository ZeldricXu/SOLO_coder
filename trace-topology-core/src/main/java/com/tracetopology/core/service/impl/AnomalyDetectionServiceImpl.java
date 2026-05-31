package com.tracetopology.core.service.impl;

import com.tracetopology.api.service.AnomalyDetectionService;
import com.tracetopology.core.validation.ParamValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RequiredArgsConstructor
public class AnomalyDetectionServiceImpl implements AnomalyDetectionService {

    private final Map<String, AnomalyDetector> detectors = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> algorithmConfigs = new ConcurrentHashMap<>();

    public AnomalyDetectionServiceImpl() {
        detectors.put("threshold", new ThresholdDetector());
        detectors.put("zscore", new ZScoreDetector());
        detectors.put("moving_average", new MovingAverageDetector());
        detectors.put("ewma", new EWMADetector());
    }

    @Override
    public List<Map<String, Object>> detectAnomalies(String metricName, Map<String, String> dimensions,
                                                      Instant startTime, Instant endTime, String algorithm) {
        ParamValidator.validateNotBlank(metricName, "metricName");
        ParamValidator.validateNotNull(dimensions, "dimensions");
        ParamValidator.validateNotNull(startTime, "startTime");
        ParamValidator.validateNotNull(endTime, "endTime");

        AnomalyDetector detector = getDetector(algorithm);
        List<Map<String, Object>> historicalData = loadHistoricalData(metricName, dimensions, startTime, endTime);

        List<Map<String, Object>> anomalies = new ArrayList<>();
        for (Map<String, Object> dataPoint : historicalData) {
            double value = ((Number) dataPoint.get("value")).doubleValue();
            long timestamp = ((Number) dataPoint.get("timestamp")).longValue();

            Map<String, Object> anomaly = detector.detect(metricName, value, dimensions, algorithmConfigs.get(algorithm));
            if ((boolean) anomaly.getOrDefault("isAnomaly", false)) {
                anomaly.put("timestamp", timestamp);
                anomaly.put("value", value);
                anomalies.add(anomaly);
            }
        }

        return anomalies;
    }

    @Override
    public Map<String, Object> detectAnomaly(String metricName, double currentValue, Map<String, String> dimensions,
                                              String algorithm) {
        ParamValidator.validateNotBlank(metricName, "metricName");
        ParamValidator.validateNotNull(dimensions, "dimensions");
        ParamValidator.validateNotBlank(algorithm, "algorithm");

        AnomalyDetector detector = getDetector(algorithm);
        Map<String, Object> config = algorithmConfigs.getOrDefault(algorithm, Collections.emptyMap());

        Map<String, Object> result = detector.detect(metricName, currentValue, dimensions, config);
        result.put("metricName", metricName);
        result.put("dimensions", dimensions);
        result.put("algorithm", algorithm);

        if ((boolean) result.getOrDefault("isAnomaly", false)) {
            log.warn("检测到异常: metric={}, value={}, severity={}",
                    metricName, currentValue, result.get("severity"));
        }

        return result;
    }

    @Override
    public void trainModel(String metricName, Map<String, String> dimensions, List<Map<String, Object>> historicalData) {
        ParamValidator.validateNotBlank(metricName, "metricName");
        ParamValidator.validateNotNull(dimensions, "dimensions");
        ParamValidator.validateNotNull(historicalData, "historicalData");

        for (AnomalyDetector detector : detectors.values()) {
            detector.train(metricName, dimensions, historicalData);
        }

        log.info("模型训练完成: metric={}, samples={}", metricName, historicalData.size());
    }

    @Override
    public Map<String, Object> getBaseline(String metricName, Map<String, String> dimensions, String algorithm) {
        ParamValidator.validateNotBlank(metricName, "metricName");
        ParamValidator.validateNotNull(dimensions, "dimensions");
        ParamValidator.validateNotBlank(algorithm, "algorithm");

        AnomalyDetector detector = getDetector(algorithm);
        return detector.getBaseline(metricName, dimensions);
    }

    @Override
    public List<String> getSupportedAlgorithms() {
        return new ArrayList<>(detectors.keySet());
    }

    @Override
    public void configureAlgorithm(String algorithmName, Map<String, Object> parameters) {
        ParamValidator.validateNotBlank(algorithmName, "algorithmName");
        ParamValidator.validateNotNull(parameters, "parameters");

        if (!detectors.containsKey(algorithmName)) {
            throw new IllegalArgumentException("不支持的算法: " + algorithmName);
        }

        algorithmConfigs.put(algorithmName, parameters);
        log.info("算法配置已更新: algorithm={}, params={}", algorithmName, parameters);
    }

    private AnomalyDetector getDetector(String algorithm) {
        AnomalyDetector detector = detectors.get(algorithm);
        if (detector == null) {
            throw new IllegalArgumentException("不支持的算法: " + algorithm);
        }
        return detector;
    }

    private List<Map<String, Object>> loadHistoricalData(String metricName, Map<String, String> dimensions,
                                                          Instant startTime, Instant endTime) {
        List<Map<String, Object>> data = new ArrayList<>();
        long start = startTime.toEpochMilli();
        long end = endTime.toEpochMilli();
        long step = 60000;

        Random random = new Random();
        for (long t = start; t < end; t += step) {
            Map<String, Object> point = new HashMap<>();
            point.put("timestamp", t);
            point.put("value", 50 + random.nextGaussian() * 10);
            data.add(point);
        }

        return data;
    }

    interface AnomalyDetector {
        Map<String, Object> detect(String metricName, double value, Map<String, String> dimensions,
                                    Map<String, Object> config);

        void train(String metricName, Map<String, String> dimensions, List<Map<String, Object>> historicalData);

        Map<String, Object> getBaseline(String metricName, Map<String, String> dimensions);
    }

    static class ThresholdDetector implements AnomalyDetector {
        private final Map<String, Double> upperThresholds = new ConcurrentHashMap<>();
        private final Map<String, Double> lowerThresholds = new ConcurrentHashMap<>();

        @Override
        public Map<String, Object> detect(String metricName, double value, Map<String, String> dimensions,
                                           Map<String, Object> config) {
            String key = metricName + dimensions;
            double upper = upperThresholds.getOrDefault(key, (Double) config.getOrDefault("upperThreshold", 100.0));
            double lower = lowerThresholds.getOrDefault(key, (Double) config.getOrDefault("lowerThreshold", 0.0));

            Map<String, Object> result = new HashMap<>();
            boolean isAnomaly = value > upper || value < lower;
            result.put("isAnomaly", isAnomaly);
            result.put("upperThreshold", upper);
            result.put("lowerThreshold", lower);

            if (isAnomaly) {
                String severity = value > upper * 1.5 || value < lower * 0.5 ? "critical" : "warning";
                result.put("severity", severity);
                result.put("deviation", value > upper ? (value - upper) / upper : (lower - value) / lower);
            }

            return result;
        }

        @Override
        public void train(String metricName, Map<String, String> dimensions, List<Map<String, Object>> historicalData) {
            String key = metricName + dimensions;
            double sum = 0, sumSq = 0;
            int n = historicalData.size();

            for (Map<String, Object> point : historicalData) {
                double v = ((Number) point.get("value")).doubleValue();
                sum += v;
                sumSq += v * v;
            }

            double mean = sum / n;
            double std = Math.sqrt(sumSq / n - mean * mean);
            upperThresholds.put(key, mean + 3 * std);
            lowerThresholds.put(key, mean - 3 * std);
        }

        @Override
        public Map<String, Object> getBaseline(String metricName, Map<String, String> dimensions) {
            String key = metricName + dimensions;
            Map<String, Object> baseline = new HashMap<>();
            baseline.put("upperThreshold", upperThresholds.get(key));
            baseline.put("lowerThreshold", lowerThresholds.get(key));
            return baseline;
        }
    }

    static class ZScoreDetector implements AnomalyDetector {
        private final Map<String, double[]> stats = new ConcurrentHashMap<>();

        @Override
        public Map<String, Object> detect(String metricName, double value, Map<String, String> dimensions,
                                           Map<String, Object> config) {
            String key = metricName + dimensions;
            double[] stat = stats.getOrDefault(key, new double[]{50, 10});
            double mean = stat[0];
            double std = stat[1];
            double zScore = std > 0 ? (value - mean) / std : 0;
            double threshold = (Double) config.getOrDefault("threshold", 3.0);

            Map<String, Object> result = new HashMap<>();
            result.put("isAnomaly", Math.abs(zScore) > threshold);
            result.put("zScore", zScore);
            result.put("mean", mean);
            result.put("std", std);
            result.put("threshold", threshold);

            if (Math.abs(zScore) > threshold) {
                result.put("severity", Math.abs(zScore) > threshold * 1.5 ? "critical" : "warning");
            }

            return result;
        }

        @Override
        public void train(String metricName, Map<String, String> dimensions, List<Map<String, Object>> historicalData) {
            String key = metricName + dimensions;
            double sum = 0, sumSq = 0;
            int n = historicalData.size();

            for (Map<String, Object> point : historicalData) {
                double v = ((Number) point.get("value")).doubleValue();
                sum += v;
                sumSq += v * v;
            }

            double mean = sum / n;
            double std = Math.sqrt(sumSq / n - mean * mean);
            stats.put(key, new double[]{mean, std});
        }

        @Override
        public Map<String, Object> getBaseline(String metricName, Map<String, String> dimensions) {
            String key = metricName + dimensions;
            double[] stat = stats.getOrDefault(key, new double[]{0, 0});
            Map<String, Object> baseline = new HashMap<>();
            baseline.put("mean", stat[0]);
            baseline.put("std", stat[1]);
            return baseline;
        }
    }

    static class MovingAverageDetector implements AnomalyDetector {
        private final Map<String, LinkedList<Double>> windows = new ConcurrentHashMap<>();
        private final int windowSize = 20;

        @Override
        public Map<String, Object> detect(String metricName, double value, Map<String, String> dimensions,
                                           Map<String, Object> config) {
            String key = metricName + dimensions;
            LinkedList<Double> window = windows.computeIfAbsent(key, k -> new LinkedList<>());

            Map<String, Object> result = new HashMap<>();
            if (window.size() < windowSize) {
                window.add(value);
                result.put("isAnomaly", false);
                result.put("reason", "insufficient_data");
                return result;
            }

            double sum = 0;
            for (Double v : window) sum += v;
            double avg = sum / window.size();
            double deviation = Math.abs(value - avg) / avg;
            double threshold = (Double) config.getOrDefault("deviationThreshold", 0.3);

            window.removeFirst();
            window.add(value);

            result.put("isAnomaly", deviation > threshold);
            result.put("movingAverage", avg);
            result.put("deviation", deviation);
            result.put("threshold", threshold);

            if (deviation > threshold) {
                result.put("severity", deviation > threshold * 1.5 ? "critical" : "warning");
            }

            return result;
        }

        @Override
        public void train(String metricName, Map<String, String> dimensions, List<Map<String, Object>> historicalData) {
            String key = metricName + dimensions;
            LinkedList<Double> window = new LinkedList<>();
            for (int i = Math.max(0, historicalData.size() - windowSize); i < historicalData.size(); i++) {
                double v = ((Number) historicalData.get(i).get("value")).doubleValue();
                window.add(v);
            }
            windows.put(key, window);
        }

        @Override
        public Map<String, Object> getBaseline(String metricName, Map<String, String> dimensions) {
            String key = metricName + dimensions;
            LinkedList<Double> window = windows.get(key);
            Map<String, Object> baseline = new HashMap<>();
            if (window != null && !window.isEmpty()) {
                double sum = 0;
                for (Double v : window) sum += v;
                baseline.put("movingAverage", sum / window.size());
                baseline.put("windowSize", window.size());
            }
            return baseline;
        }
    }

    static class EWMADetector implements AnomalyDetector {
        private final Map<String, Double> ewmaValues = new ConcurrentHashMap<>();
        private final double alpha = 0.3;

        @Override
        public Map<String, Object> detect(String metricName, double value, Map<String, String> dimensions,
                                           Map<String, Object> config) {
            String key = metricName + dimensions;
            Double currentEwma = ewmaValues.get(key);

            Map<String, Object> result = new HashMap<>();
            if (currentEwma == null) {
                ewmaValues.put(key, value);
                result.put("isAnomaly", false);
                return result;
            }

            double newEwma = alpha * value + (1 - alpha) * currentEwma;
            ewmaValues.put(key, newEwma);

            double deviation = Math.abs(value - newEwma) / newEwma;
            double threshold = (Double) config.getOrDefault("deviationThreshold", 0.25);

            result.put("isAnomaly", deviation > threshold);
            result.put("ewma", newEwma);
            result.put("deviation", deviation);

            if (deviation > threshold) {
                result.put("severity", deviation > threshold * 1.5 ? "critical" : "warning");
            }

            return result;
        }

        @Override
        public void train(String metricName, Map<String, String> dimensions, List<Map<String, Object>> historicalData) {
            String key = metricName + dimensions;
            if (historicalData.isEmpty()) return;

            double ewma = ((Number) historicalData.get(0).get("value")).doubleValue();
            for (int i = 1; i < historicalData.size(); i++) {
                double v = ((Number) historicalData.get(i).get("value")).doubleValue();
                ewma = alpha * v + (1 - alpha) * ewma;
            }
            ewmaValues.put(key, ewma);
        }

        @Override
        public Map<String, Object> getBaseline(String metricName, Map<String, String> dimensions) {
            String key = metricName + dimensions;
            Map<String, Object> baseline = new HashMap<>();
            baseline.put("ewma", ewmaValues.get(key));
            baseline.put("alpha", alpha);
            return baseline;
        }
    }
}
