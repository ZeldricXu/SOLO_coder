package com.datateam.loganalyzer.anomaly;

import com.datateam.loganalyzer.model.AnomalyResult;
import com.datateam.loganalyzer.model.TimeSeriesPoint;

import java.util.*;

public class AnomalyDetectionEngine {

    private final List<AnomalyDetector> detectors;
    private final Set<String> enabledAlgorithms;
    private String metric;
    private int baselinePeriodPoints;
    private final AnomalyDetectorServiceLoader serviceLoader;

    public AnomalyDetectionEngine() {
        this("errors");
    }

    public AnomalyDetectionEngine(String metric) {
        this.metric = metric;
        this.detectors = new ArrayList<>();
        this.enabledAlgorithms = new LinkedHashSet<>();
        this.baselinePeriodPoints = 30;
        this.serviceLoader = AnomalyDetectorServiceLoader.getInstance();
        this.serviceLoader.initialize();

        enableAlgorithm(ZScoreDetector.class.getName());
        enableAlgorithm(MovingAverageDetector.class.getName());
    }

    public void enableAlgorithm(String algorithmClassName) {
        enabledAlgorithms.add(algorithmClassName);
    }

    public void disableAlgorithm(String algorithmClassName) {
        enabledAlgorithms.remove(algorithmClassName);
    }

    public void configureAlgorithm(String algorithmClassName, Map<String, Object> config) {
        for (AnomalyDetector detector : detectors) {
            if (detector.getAlgorithmClassName().equals(algorithmClassName) ||
                detector.getName().equals(algorithmClassName)) {
                detector.configure(config);
                return;
            }
        }

        AnomalyDetector detector = serviceLoader.getDetector(algorithmClassName, config);
        if (detector != null) {
            detectors.add(detector);
            enabledAlgorithms.add(algorithmClassName);
        }
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

        initializeDetectors();

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

        for (AnomalyDetector detector : detectors) {
            if (enabledAlgorithms.contains(detector.getAlgorithmClassName()) ||
                enabledAlgorithms.contains(detector.getName())) {
                detector.trainFromTimeSeries(trainingData, metric);
                List<AnomalyResult> results = detector.detectFromTimeSeries(detectionData, metric);
                allResults.addAll(results);
            }
        }

        return deduplicateAndMerge(allResults);
    }

    private void initializeDetectors() {
        for (String algoName : enabledAlgorithms) {
            boolean exists = false;
            for (AnomalyDetector detector : detectors) {
                if (detector.getAlgorithmClassName().equals(algoName) ||
                    detector.getName().equals(algoName)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                AnomalyDetector detector = serviceLoader.getDetector(algoName);
                if (detector != null) {
                    detectors.add(detector);
                }
            }
        }
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

    @Deprecated
    public void configureZScore(double threshold, int minDataPoints) {
        Map<String, Object> config = new HashMap<>();
        config.put("threshold", threshold);
        config.put("minDataPoints", minDataPoints);
        configureAlgorithm(ZScoreDetector.class.getName(), config);
    }

    @Deprecated
    public void configureMovingAverage(int windowSize, double sigmaMultiplier, int minDataPoints) {
        Map<String, Object> config = new HashMap<>();
        config.put("windowSize", windowSize);
        config.put("sigmaMultiplier", sigmaMultiplier);
        config.put("minDataPoints", minDataPoints);
        configureAlgorithm(MovingAverageDetector.class.getName(), config);
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public String getMetric() {
        return metric;
    }

    @Deprecated
    public void setUseZScore(boolean useZScore) {
        if (useZScore) {
            enableAlgorithm(ZScoreDetector.class.getName());
        } else {
            disableAlgorithm(ZScoreDetector.class.getName());
        }
    }

    @Deprecated
    public void setUseMovingAverage(boolean useMovingAverage) {
        if (useMovingAverage) {
            enableAlgorithm(MovingAverageDetector.class.getName());
        } else {
            disableAlgorithm(MovingAverageDetector.class.getName());
        }
    }

    public void setBaselinePeriodPoints(int baselinePeriodPoints) {
        this.baselinePeriodPoints = baselinePeriodPoints;
    }

    @Deprecated
    public BaselineModel getZScoreBaseline() {
        return getDetectorBaseline(ZScoreDetector.class.getName());
    }

    @Deprecated
    public BaselineModel getMovingAverageBaseline() {
        return getDetectorBaseline(MovingAverageDetector.class.getName());
    }

    private BaselineModel getDetectorBaseline(String detectorName) {
        for (AnomalyDetector detector : detectors) {
            if (detector.getAlgorithmClassName().equals(detectorName) ||
                detector.getName().equals(detectorName)) {
                return detector.getBaseline();
            }
        }
        return null;
    }

    public List<AnomalyDetector> getDetectors() {
        return Collections.unmodifiableList(detectors);
    }

    public Set<String> getEnabledAlgorithms() {
        return Collections.unmodifiableSet(enabledAlgorithms);
    }

    public AnomalyDetectorServiceLoader getServiceLoader() {
        return serviceLoader;
    }
}
