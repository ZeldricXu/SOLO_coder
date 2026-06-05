package com.datateam.loganalyzer.anomaly;

import com.datateam.loganalyzer.model.AnomalyResult;
import com.datateam.loganalyzer.model.TimeSeriesPoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class MovingAverageDetector {

    private int windowSize;
    private double sigmaMultiplier;
    private int minDataPoints;
    private BaselineModel baseline;

    public MovingAverageDetector() {
        this(10, 3.0, 20);
    }

    public MovingAverageDetector(int windowSize, double sigmaMultiplier) {
        this(windowSize, sigmaMultiplier, 20);
    }

    public MovingAverageDetector(int windowSize, double sigmaMultiplier, int minDataPoints) {
        this.windowSize = windowSize;
        this.sigmaMultiplier = sigmaMultiplier;
        this.minDataPoints = minDataPoints;
    }

    public void train(List<Double> baselineData, String metric) {
        this.baseline = new BaselineModel(metric);
        this.baseline.setWindowSize(windowSize);
        if (baselineData != null && baselineData.size() >= minDataPoints) {
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

        if (values == null || values.size() < windowSize) {
            return results;
        }

        List<Double> movingAverages = calculateMovingAverages(values);
        double residualStdDev = calculateResidualStdDev(values, movingAverages);

        for (int i = 0; i < values.size(); i++) {
            Double value = values.get(i);
            if (value == null) continue;

            double expected = movingAverages.get(i);
            double residual = value - expected;
            double normalizedResidual = residualStdDev > 0 ? residual / residualStdDev : 0;

            boolean isAnomaly = Math.abs(normalizedResidual) > sigmaMultiplier;

            if (isAnomaly || Math.abs(normalizedResidual) > sigmaMultiplier * 0.75) {
                AnomalyResult result = new AnomalyResult();
                result.setType(AnomalyResult.AnomalyType.MOVING_AVERAGE_RESIDUAL);
                result.setTimestamp(timestamps != null && i < timestamps.size() ?
                    timestamps.get(i) : Instant.now());
                result.setObservedValue(value);
                result.setExpectedValue(expected);
                result.setDeviation(residual);
                result.setzScore(normalizedResidual);
                result.setThreshold(sigmaMultiplier);
                result.setAnomaly(isAnomaly);
                result.setMetric(baseline != null ? baseline.getMetric() : "unknown");

                String desc = String.format(
                    "Residual=%.2f (%.2fσ) exceeds %.2fσ threshold (expected=%.2f)",
                    residual, normalizedResidual, sigmaMultiplier, expected
                );
                result.setDescription(desc);

                results.add(result);
            }
        }

        return results;
    }

    public List<AnomalyResult> detectFromTimeSeries(List<TimeSeriesPoint> points, String metric) {
        List<Double> values = extractMetricValues(points, metric);
        List<Instant> timestamps = new ArrayList<>();
        for (TimeSeriesPoint p : points) {
            timestamps.add(p.getWindowStart());
        }
        return detect(values, timestamps);
    }

    private List<Double> calculateMovingAverages(List<Double> values) {
        List<Double> averages = new ArrayList<>();

        for (int i = 0; i < values.size(); i++) {
            double sum = 0;
            int count = 0;
            int start = Math.max(0, i - windowSize + 1);

            for (int j = start; j <= i; j++) {
                if (values.get(j) != null) {
                    sum += values.get(j);
                    count++;
                }
            }

            double avg = count > 0 ? sum / count : 0;
            averages.add(avg);
        }

        return averages;
    }

    private double calculateResidualStdDev(List<Double> values, List<Double> movingAverages) {
        if (values.size() <= windowSize) {
            return 0;
        }

        double sumSquared = 0;
        int count = 0;

        for (int i = windowSize; i < values.size(); i++) {
            if (values.get(i) != null) {
                double residual = values.get(i) - movingAverages.get(i);
                sumSquared += residual * residual;
                count++;
            }
        }

        return count > 0 ? Math.sqrt(sumSquared / count) : 0;
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
                return (double) point.getTotalCount();
        }
    }

    public int getWindowSize() {
        return windowSize;
    }

    public void setWindowSize(int windowSize) {
        this.windowSize = windowSize;
    }

    public double getSigmaMultiplier() {
        return sigmaMultiplier;
    }

    public void setSigmaMultiplier(double sigmaMultiplier) {
        this.sigmaMultiplier = sigmaMultiplier;
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
