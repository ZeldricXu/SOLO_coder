package com.datateam.loganalyzer.anomaly;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import java.util.ArrayList;
import java.util.List;

public class BaselineModel {
    private final String metric;
    private final String dimension;
    private double mean;
    private double stdDev;
    private double variance;
    private double min;
    private double max;
    private double percentile95;
    private double percentile99;
    private List<Double> movingAverages;
    private int windowSize;
    private final List<Double> historicalData;

    public BaselineModel(String metric) {
        this(metric, null);
    }

    public BaselineModel(String metric, String dimension) {
        this.metric = metric;
        this.dimension = dimension;
        this.historicalData = new ArrayList<>();
        this.movingAverages = new ArrayList<>();
        this.windowSize = 10;
    }

    public void learn(List<Double> data) {
        if (data == null || data.isEmpty()) {
            return;
        }

        this.historicalData.addAll(data);

        DescriptiveStatistics stats = new DescriptiveStatistics();
        for (Double value : data) {
            if (value != null) {
                stats.addValue(value);
            }
        }

        this.mean = stats.getMean();
        this.stdDev = stats.getStandardDeviation();
        this.variance = stats.getVariance();
        this.min = stats.getMin();
        this.max = stats.getMax();
        this.percentile95 = stats.getPercentile(95);
        this.percentile99 = stats.getPercentile(99);

        calculateMovingAverages(data);
    }

    private void calculateMovingAverages(List<Double> data) {
        this.movingAverages = new ArrayList<>();
        if (data.size() < windowSize) {
            double sum = 0;
            for (Double v : data) {
                if (v != null) sum += v;
            }
            double avg = sum / data.size();
            for (int i = 0; i < data.size(); i++) {
                movingAverages.add(avg);
            }
            return;
        }

        double windowSum = 0;
        for (int i = 0; i < windowSize; i++) {
            if (data.get(i) != null) {
                windowSum += data.get(i);
            }
        }

        for (int i = 0; i < data.size(); i++) {
            if (i < windowSize) {
                movingAverages.add(windowSum / windowSize);
            } else {
                if (data.get(i) != null) {
                    windowSum += data.get(i);
                }
                if (data.get(i - windowSize) != null) {
                    windowSum -= data.get(i - windowSize);
                }
                movingAverages.add(windowSum / windowSize);
            }
        }
    }

    public double calculateZScore(double value) {
        if (stdDev == 0) {
            return value == mean ? 0 : Double.POSITIVE_INFINITY;
        }
        return (value - mean) / stdDev;
    }

    public double calculateResidual(double value, int index) {
        if (index < 0 || index >= movingAverages.size()) {
            return value - mean;
        }
        return value - movingAverages.get(index);
    }

    public boolean isAnomalyZScore(double value, double threshold) {
        return Math.abs(calculateZScore(value)) > threshold;
    }

    public boolean isAnomalyResidual(double value, int index, double sigmaMultiplier) {
        double residual = calculateResidual(value, index);
        return Math.abs(residual) > sigmaMultiplier * stdDev;
    }

    public double getUpperBound(double sigmaMultiplier) {
        return mean + sigmaMultiplier * stdDev;
    }

    public double getLowerBound(double sigmaMultiplier) {
        return mean - sigmaMultiplier * stdDev;
    }

    public String getMetric() {
        return metric;
    }

    public String getDimension() {
        return dimension;
    }

    public double getMean() {
        return mean;
    }

    public double getStdDev() {
        return stdDev;
    }

    public double getVariance() {
        return variance;
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public double getPercentile95() {
        return percentile95;
    }

    public double getPercentile99() {
        return percentile99;
    }

    public List<Double> getMovingAverages() {
        return movingAverages;
    }

    public int getWindowSize() {
        return windowSize;
    }

    public void setWindowSize(int windowSize) {
        this.windowSize = windowSize;
    }

    public List<Double> getHistoricalData() {
        return historicalData;
    }

    public int getDataSize() {
        return historicalData.size();
    }
}
