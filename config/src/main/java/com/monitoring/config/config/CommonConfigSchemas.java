package com.monitoring.config.config;

import com.monitoring.config.validator.ConfigValidator;

import java.util.List;

public class CommonConfigSchemas {

    private CommonConfigSchemas() {
    }

    public static final ConfigValidator.ConfigSchema ANOMALY_DETECTION_SCHEMA = new ConfigValidator.ConfigSchema(List.of(
            new ConfigValidator.ConfigField("algorithm", String.class, false, "zscore", value -> {
                String alg = (String) value;
                return List.of("zscore", "threshold", "ma", "ewma", "isolation_forest").contains(alg)
                        ? null : "Invalid algorithm, must be one of: zscore, threshold, ma, ewma, isolation_forest";
            }),
            new ConfigValidator.ConfigField("sensitivity", Double.class, false, 3.0, value -> {
                double s = (Double) value;
                return s > 0 && s <= 10 ? null : "Sensitivity must be between 0 and 10";
            }),
            new ConfigValidator.ConfigField("windowSize", Integer.class, false, 100, value -> {
                int w = (Integer) value;
                return w >= 10 && w <= 10000 ? null : "Window size must be between 10 and 10000";
            }),
            new ConfigValidator.ConfigField("lookbackPeriod", Long.class, false, 3600000L, null)
    ));

    public static final ConfigValidator.ConfigSchema SAMPLING_SCHEMA = new ConfigValidator.ConfigSchema(List.of(
            new ConfigValidator.ConfigField("samplingRate", Double.class, false, 0.1, value -> {
                double r = (Double) value;
                return r >= 0 && r <= 1 ? null : "Sampling rate must be between 0 and 1";
            }),
            new ConfigValidator.ConfigField("tailSamplingEnabled", Boolean.class, false, true, null),
            new ConfigValidator.ConfigField("errorThreshold", Double.class, false, 0.05, value -> {
                double t = (Double) value;
                return t >= 0 && t <= 1 ? null : "Error threshold must be between 0 and 1";
            }),
            new ConfigValidator.ConfigField("latencyThresholdMs", Long.class, false, 5000L, null)
    ));

    public static final ConfigValidator.ConfigSchema ALERT_SCHEMA = new ConfigValidator.ConfigSchema(List.of(
            new ConfigValidator.ConfigField("evaluationInterval", Long.class, false, 60000L, value -> {
                long i = (Long) value;
                return i >= 1000 && i <= 3600000 ? null : "Evaluation interval must be between 1s and 1h";
            }),
            new ConfigValidator.ConfigField("notificationChannels", List.class, false, List.of(), null),
            new ConfigValidator.ConfigField("dedupEnabled", Boolean.class, false, true, null),
            new ConfigValidator.ConfigField("deduplicationWindow", Long.class, false, 300000L, null)
    ));
}
