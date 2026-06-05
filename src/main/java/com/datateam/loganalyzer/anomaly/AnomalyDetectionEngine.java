package com.datateam.loganalyzer.anomaly;

import com.datateam.loganalyzer.model.AnomalyResult;
import com.datateam.loganalyzer.model.TimeSeriesPoint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AnomalyDetectionEngine {

    private ZScoreDetector zScoreDetector;
    private MovingAverageDetector movingAverageDetector;
    private boolean useZScore;
    private boolean useMovingAverage;
    private String metric;
    private int baselinePeriodPoints;

    public AnomalyDetectionEngine() {
        this("errors");
    }

    public AnomalyDetectionEngine(String metric) {
        this.metric = metric;
        this.useZScore = true;
        this.useMovingAverage = true;
        this.zScoreDetector = new ZScoreDetector(3.0, 10);
        this.movingAverageDetector = new MovingAverageDetector(10, 3.0, 20);
        this.baselinePeriodPoints = 30;
    }

    public List<AnomalyResult> analyze(List<TimeSeriesPoint> timeSeries) {
        return analyze(timeSeries, null);
    }

    public List<AnomalyResult> analyze(List<TimeSeriesPoint> timeSeries,
                                       List<TimeSeriesPoint> baselineData) {
        List<AnomalyResult> allResults = new ArrayList<>();

        if (timeSeries == null || timeSeries.isEmpty()) {
            return allResults;
        }

        List<TimeSeriesPoint> trainingData = new ArrayList<>();
        List<TimeSeriesPoint> detectionData = new ArrayList<>();

        if (baselineData != null && !baselineData.isEmpty()) {
            trainingData.addAll(baselineData);
            detectionData.addAll(timeSeries);
        } else {
            if (timeSeries.size() <= baselinePeriodPoints) {
                trainingData.addAll(timeSeries);
                detectionData.addAll(timeSeries);
            } else {
                trainingData.addAll(timeSeries.subList(0, baselinePeriodPoints));
                detectionData.addAll(timeSeries.subList(baselinePeriodPoints, timeSeries.size()));
            }
        }

        if (useZScore) {
            zScoreDetector.trainFromTimeSeries(trainingData, metric);
            List<AnomalyResult> zResults = zScoreDetector.detectFromTimeSeries(detectionData, metric);
            allResults.addAll(zResults);
        }

        if (useMovingAverage) {
            movingAverageDetector.trainFromTimeSeries(trainingData, metric);
            List<AnomalyResult> maResults = movingAverageDetector.detectFromTimeSeries(detectionData, metric);
            allResults.addAll(maResults);
        }

        return deduplicateAndMerge(allResults);
    }

    private List<AnomalyResult> deduplicateAndMerge(List<AnomalyResult> results) {
        if (results.size() <= 1) {
            return results;
        }

        results.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));

        List<AnomalyResult> merged = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        for (AnomalyResult result : results) {
            String key = result.getTimestamp().toString() + "|" + result.getType();
            if (seenKeys.contains(key)) {
                continue;
            }
            seenKeys.add(key);
            merged.add(result);
        }

        List<AnomalyResult> finalResults = new ArrayList<>();
        for (AnomalyResult r : merged) {
            if (r.isAnomaly()) {
                finalResults.add(r);
            }
        }

        if (finalResults.isEmpty()) {
            for (AnomalyResult r : merged) {
                if (Math.abs(r.getzScore()) > 2.5) {
                    finalResults.add(r);
                }
            }
        }

        return finalResults;
    }

    public void configureZScore(double threshold, int minDataPoints) {
        this.zScoreDetector = new ZScoreDetector(threshold, minDataPoints);
    }

    public void configureMovingAverage(int windowSize, double sigmaMultiplier, int minDataPoints) {
        this.movingAverageDetector = new MovingAverageDetector(windowSize, sigmaMultiplier, minDataPoints);
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public String getMetric() {
        return metric;
    }

    public void setUseZScore(boolean useZScore) {
        this.useZScore = useZScore;
    }

    public void setUseMovingAverage(boolean useMovingAverage) {
        this.useMovingAverage = useMovingAverage;
    }

    public void setBaselinePeriodPoints(int baselinePeriodPoints) {
        this.baselinePeriodPoints = baselinePeriodPoints;
    }

    public BaselineModel getZScoreBaseline() {
        return zScoreDetector != null ? zScoreDetector.getBaseline() : null;
    }

    public BaselineModel getMovingAverageBaseline() {
        return movingAverageDetector != null ? movingAverageDetector.getBaseline() : null;
    }
}
