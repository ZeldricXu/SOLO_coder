package com.loganalytics.detector.config;

import com.loganalytics.common.config.AppConfig;

import java.time.Duration;

public class DetectorConfig {
    private String applicationId;
    private String bootstrapServers;
    private String inputTopic;
    private String outputTopic;

    private double similarityThreshold;
    private int maxTreeDepth;
    private int maxChildren;
    private double similarityDecayRate;

    private int frequencyWindowMinutes;
    private int baselineHistoryDays;
    private double sigmaThreshold;
    private int minBaselinePoints;

    private int baselineWindowMinutes;
    private int baselineWarmupMinutes;
    private double frequencySigmaThreshold;
    private int anomalyCooldownSeconds;
    private int coldStartDefaultThreshold;

    private int correlationWindowSeconds;
    private int correlationMaxPatterns;

    private boolean frequencyDetectionEnabled;
    private boolean contentDetectionEnabled;
    private boolean correlationDetectionEnabled;

    private int anomalyCooldownMinutes;

    public DetectorConfig() {}

    public static DetectorConfig fromAppConfig(AppConfig config) {
        DetectorConfig dc = new DetectorConfig();

        dc.setApplicationId(config.getString("detector.application.id", "pattern-detector"));
        dc.setBootstrapServers(config.getString("kafka.bootstrap.servers", "localhost:9092"));
        dc.setInputTopic(config.getString("detector.input.topic", "error-logs"));
        dc.setOutputTopic(config.getString("detector.output.topic", "anomalies"));

        dc.setSimilarityThreshold(config.getDouble("detector.similarity.threshold", 0.7));
        dc.setMaxTreeDepth(config.getInt("detector.tree.max.depth", 4));
        dc.setMaxChildren(config.getInt("detector.tree.max.children", 100));
        dc.setSimilarityDecayRate(config.getDouble("detector.similarity.decay", 0.01));

        dc.setFrequencyWindowMinutes(config.getInt("detector.frequency.window.minutes", 5));
        dc.setBaselineHistoryDays(config.getInt("detector.baseline.history.days", 14));
        dc.setSigmaThreshold(config.getDouble("detector.sigma.threshold", 3.0));
        dc.setMinBaselinePoints(config.getInt("detector.baseline.min.points", 100));

        dc.setCorrelationWindowSeconds(config.getInt("detector.correlation.window.seconds", 300));
        dc.setCorrelationMaxPatterns(config.getInt("detector.correlation.max.patterns", 1000));

        dc.setFrequencyDetectionEnabled(config.getBoolean("detector.frequency.enabled", true));
        dc.setContentDetectionEnabled(config.getBoolean("detector.content.enabled", true));
        dc.setCorrelationDetectionEnabled(config.getBoolean("detector.correlation.enabled", true));

        dc.setAnomalyCooldownMinutes(config.getInt("detector.anomaly.cooldown.minutes", 5));

        return dc;
    }

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }

    public String getInputTopic() { return inputTopic; }
    public void setInputTopic(String inputTopic) { this.inputTopic = inputTopic; }

    public String getOutputTopic() { return outputTopic; }
    public void setOutputTopic(String outputTopic) { this.outputTopic = outputTopic; }

    public double getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }

    public int getMaxTreeDepth() { return maxTreeDepth; }
    public void setMaxTreeDepth(int maxTreeDepth) { this.maxTreeDepth = maxTreeDepth; }

    public int getMaxChildren() { return maxChildren; }
    public void setMaxChildren(int maxChildren) { this.maxChildren = maxChildren; }

    public double getSimilarityDecayRate() { return similarityDecayRate; }
    public void setSimilarityDecayRate(double similarityDecayRate) { this.similarityDecayRate = similarityDecayRate; }

    public int getFrequencyWindowMinutes() { return frequencyWindowMinutes; }
    public void setFrequencyWindowMinutes(int frequencyWindowMinutes) { this.frequencyWindowMinutes = frequencyWindowMinutes; }

    public int getBaselineHistoryDays() { return baselineHistoryDays; }
    public void setBaselineHistoryDays(int baselineHistoryDays) { this.baselineHistoryDays = baselineHistoryDays; }

    public double getSigmaThreshold() { return sigmaThreshold; }
    public void setSigmaThreshold(double sigmaThreshold) { this.sigmaThreshold = sigmaThreshold; }

    public int getMinBaselinePoints() { return minBaselinePoints; }
    public void setMinBaselinePoints(int minBaselinePoints) { this.minBaselinePoints = minBaselinePoints; }

    public int getCorrelationWindowSeconds() { return correlationWindowSeconds; }
    public void setCorrelationWindowSeconds(int correlationWindowSeconds) { this.correlationWindowSeconds = correlationWindowSeconds; }

    public int getCorrelationMaxPatterns() { return correlationMaxPatterns; }
    public void setCorrelationMaxPatterns(int correlationMaxPatterns) { this.correlationMaxPatterns = correlationMaxPatterns; }

    public boolean isFrequencyDetectionEnabled() { return frequencyDetectionEnabled; }
    public void setFrequencyDetectionEnabled(boolean frequencyDetectionEnabled) { this.frequencyDetectionEnabled = frequencyDetectionEnabled; }

    public boolean isContentDetectionEnabled() { return contentDetectionEnabled; }
    public void setContentDetectionEnabled(boolean contentDetectionEnabled) { this.contentDetectionEnabled = contentDetectionEnabled; }

    public boolean isCorrelationDetectionEnabled() { return correlationDetectionEnabled; }
    public void setCorrelationDetectionEnabled(boolean correlationDetectionEnabled) { this.correlationDetectionEnabled = correlationDetectionEnabled; }

    public int getAnomalyCooldownMinutes() { return anomalyCooldownMinutes; }
    public void setAnomalyCooldownMinutes(int anomalyCooldownMinutes) { this.anomalyCooldownMinutes = anomalyCooldownMinutes; }

    public int getBaselineWindowMinutes() { return baselineWindowMinutes; }
    public void setBaselineWindowMinutes(int baselineWindowMinutes) { this.baselineWindowMinutes = baselineWindowMinutes; }

    public int getBaselineWarmupMinutes() { return baselineWarmupMinutes; }
    public void setBaselineWarmupMinutes(int baselineWarmupMinutes) { this.baselineWarmupMinutes = baselineWarmupMinutes; }

    public double getFrequencySigmaThreshold() { return frequencySigmaThreshold > 0 ? frequencySigmaThreshold : sigmaThreshold; }
    public void setFrequencySigmaThreshold(double frequencySigmaThreshold) { this.frequencySigmaThreshold = frequencySigmaThreshold; }

    public int getAnomalyCooldownSeconds() { return anomalyCooldownSeconds > 0 ? anomalyCooldownSeconds : anomalyCooldownMinutes * 60; }
    public void setAnomalyCooldownSeconds(int anomalyCooldownSeconds) { this.anomalyCooldownSeconds = anomalyCooldownSeconds; }

    public int getColdStartDefaultThreshold() { return coldStartDefaultThreshold; }
    public void setColdStartDefaultThreshold(int coldStartDefaultThreshold) { this.coldStartDefaultThreshold = coldStartDefaultThreshold; }
}
