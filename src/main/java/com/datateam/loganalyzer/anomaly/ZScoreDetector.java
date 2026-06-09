package com.datateam.loganalyzer.anomaly;

import com.datateam.loganalyzer.model.AnomalyResult;
import com.datateam.loganalyzer.model.TimeSeriesPoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ZScoreDetector implements AnomalyDetector {

    private double threshold;
    private int minDataPoints;
    private BaselineModel baseline;

    public ZScoreDetector() {
        this(3.0, 10);
    }

    public ZScoreDetector(double threshold) {
        this(threshold, 10);
    }

    public ZScoreDetector(double threshold, int minDataPoints) {
        this.threshold = threshold;
        this.minDataPoints = minDataPoints;
    }

    @Override
    public String getName() {
        return "zscore";
    }

    @Override
    public String getAlgorithmClassName() {
        return ZScoreDetector.class.getName();
    }

    @Override
    public void configure(Map<String, Object> config) {
        if (config == null) return;
        if (config.containsKey("threshold")) {
            this.threshold = ((Number) config.get("threshold")).doubleValue();
        }
        if (config.containsKey("minDataPoints")) {
            this.minDataPoints = ((Number) config.get("minDataPoints")).intValue();
        }
    }

    @Override
    public Map<String, Object> getConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("threshold", threshold);
        config.put("minDataPoints", minDataPoints);
        return config;
    }

    @Override
    public boolean isReady() {
        return baseline != null && baseline.getDataSize() >= minDataPoints;
    }

    public void train(List<Double> baselineData, String metric) {
        this.baseline = new BaselineModel(metric);
        if (baselineData != null && !baselineData.isEmpty()) {
            this.baseline.learn(baselineData);
        }
    }

    public void trainFromTimeSeries(List<TimeSeriesPoint> points, String metric) {
        List<Double> values = extractMetricValues(points, metric);
        train(values, metric);
    }

    public List<AnomalyResult> detect(List<Double> values) {
        return detect(values, null);
    }

    public List<AnomalyResult> detect(List<Double> values, List<Instant> timestamps) {
        List<AnomalyResult> results = new ArrayList<>();

        if (values == null || values.isEmpty()) {
            return results;
        }

        if (baseline == null || baseline.getDataSize() < minDataPoints) {
            BaselineModel tempBaseline = new BaselineModel("temp");
            tempBaseline.learn(values);
            detectWithBaseline(values, timestamps, tempBaseline, results);
        } else {
            detectWithBaseline(values, timestamps, baseline, results);
        }

        return results;
    }

    private void detectWithBaseline(List<Double> values, List<Instant> timestamps,
                                    BaselineModel model, List<AnomalyResult> results) {
        for (int i = 0; i < values.size(); i++) {
            Double value = values.get(i);
            if (value == null) continue;

            double zScore = model.calculateZScore(value);
            boolean isAnomaly = Math.abs(zScore) > threshold;

            if (isAnomaly || Math.abs(zScore) > threshold * 0.75) {
                AnomalyResult result = new AnomalyResult();
                result.setType(AnomalyResult.AnomalyType.ZSCORE);
                result.setAlgorithm(getAlgorithmClassName());
                result.setTimestamp(timestamps != null && i < timestamps.size() ?
                    timestamps.get(i) : Instant.now());
                result.setObservedValue(value);
                result.setExpectedValue(model.getMean());
                result.setDeviation(value - model.getMean());
                result.setzScore(zScore);
                result.setThreshold(threshold);
                result.setAnomaly(isAnomaly);
                result.setMetric(model.getMetric());

                String desc = String.format(
                    "Z-score=%.2f exceeds threshold %.2f (mean=%.2f, std=%.2f)",
                    zScore, threshold, model.getMean(), model.getStdDev()
                );
                result.setDescription(desc);

                results.add(result);
            }
        }
    }

    public List<AnomalyResult> detectFromTimeSeries(List<TimeSeriesPoint> points, String metric) {
        List<Double> values = extractMetricValues(points, metric);
        List<Instant> timestamps = new ArrayList<>();
        for (TimeSeriesPoint p : points) {
            timestamps.add(p.getWindowStart());
        }
        return detect(values, timestamps);
    }

    private List<Double> extractMetricValues(List<TimeSeriesPoint> points, String metric) {
        List<Double> values = new ArrayList<>();
        for (TimeSeriesPoint point : points) {
            double value = getMetricValue(point, metric);
            values.add(value);
        }
        return values;
    }

    private double getMetricValue(TimeSeriesPoint point, String metric) {
        switch (metric.toLowerCase()) {
            case "total":
            case "count":
                return (double) point.getTotalCount();
            case "error":
            case "errors":
                return (double) point.getErrorCount();
            case "warn":
            case "warnings":
                return (double) point.getWarnCount();
            case "rate":
            case "rate_per_minute":
                return point.getRatePerMinute();
            case "rate_per_second":
                return point.getRatePerSecond();
            default:
                if (metric.startsWith("service:")) {
                    String serviceName = metric.substring(8);
                    return (double) point.getServiceCounts().getOrDefault(serviceName, 0L);
                }
                if (metric.startsWith("error_type:")) {
                    String errorType = metric.substring(11);
                    return (double) point.getErrorTypeCounts().getOrDefault(errorType, 0L);
                }
                return (double) point.getTotalCount();
        }
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public int getMinDataPoints() {
        return minDataPoints;
    }

    public void setMinDataPoints(int minDataPoints) {
        this.minDataPoints = minDataPoints;
    }

    public BaselineModel getBaseline() {
        return baseline;
    }
}
