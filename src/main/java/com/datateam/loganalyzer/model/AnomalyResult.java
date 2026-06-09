package com.datateam.loganalyzer.model;

import java.time.Instant;

public class AnomalyResult {
    @Deprecated
    public enum AnomalyType {
        ZSCORE,
        MOVING_AVERAGE_RESIDUAL,
        SPIKE,
        DROP
    }

    @Deprecated
    private AnomalyType type;
    private String algorithm;
    private Instant timestamp;
    private double observedValue;
    private double expectedValue;
    private double deviation;
    private double zScore;
    private double threshold;
    private boolean isAnomaly;
    private String metric;
    private String dimension;
    private String description;

    public AnomalyResult() {
    }

    public AnomalyType getType() {
        return type;
    }

    public void setType(AnomalyType type) {
        this.type = type;
        if (type != null && this.algorithm == null) {
            switch (type) {
                case ZSCORE:
                    this.algorithm = "com.datateam.loganalyzer.anomaly.ZScoreDetector";
                    break;
                case MOVING_AVERAGE_RESIDUAL:
                    this.algorithm = "com.datateam.loganalyzer.anomaly.MovingAverageDetector";
                    break;
                default:
                    this.algorithm = type.name();
            }
        }
    }

    public String getAlgorithm() {
        return algorithm != null ? algorithm : (type != null ? type.name() : null);
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public double getObservedValue() {
        return observedValue;
    }

    public void setObservedValue(double observedValue) {
        this.observedValue = observedValue;
    }

    public double getExpectedValue() {
        return expectedValue;
    }

    public void setExpectedValue(double expectedValue) {
        this.expectedValue = expectedValue;
    }

    public double getDeviation() {
        return deviation;
    }

    public void setDeviation(double deviation) {
        this.deviation = deviation;
    }

    public double getzScore() {
        return zScore;
    }

    public void setzScore(double zScore) {
        this.zScore = zScore;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public boolean isAnomaly() {
        return isAnomaly;
    }

    public void setAnomaly(boolean anomaly) {
        isAnomaly = anomaly;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s: observed=%.2f, expected=%.2f, z-score=%.2f, anomaly=%b",
            timestamp, type, observedValue, expectedValue, zScore, isAnomaly);
    }
}
