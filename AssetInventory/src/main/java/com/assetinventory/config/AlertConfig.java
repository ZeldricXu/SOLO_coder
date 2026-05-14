package com.assetinventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "inventory.alert")
public class AlertConfig {

    private boolean enabled = true;
    private Map<String, SeverityConfig> severity = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, SeverityConfig> getSeverity() {
        return severity;
    }

    public void setSeverity(Map<String, SeverityConfig> severity) {
        this.severity = severity;
    }

    public SeverityConfig getSeverityConfig(String severityName) {
        return severity.get(severityName.toLowerCase());
    }

    public long getAlertIntervalMs(String severityName) {
        SeverityConfig config = getSeverityConfig(severityName);
        if (config == null) {
            return TimeUnit.SECONDS.toMillis(1800);
        }
        return TimeUnit.SECONDS.toMillis(config.getAlertIntervalSeconds());
    }

    public double getThreshold(String severityName) {
        SeverityConfig config = getSeverityConfig(severityName);
        if (config == null) {
            return 0.0;
        }
        return config.getThreshold();
    }

    public int getLevel(String severityName) {
        SeverityConfig config = getSeverityConfig(severityName);
        if (config == null) {
            return 4;
        }
        return config.getLevel();
    }

    public String getName(String severityName) {
        SeverityConfig config = getSeverityConfig(severityName);
        if (config == null) {
            return "未知";
        }
        return config.getName();
    }

    public String getDescription(String severityName) {
        SeverityConfig config = getSeverityConfig(severityName);
        if (config == null) {
            return "";
        }
        return config.getDescription();
    }

    public String determineSeverityByRatio(double diffRatio) {
        if (diffRatio >= getThreshold("critical")) {
            return "critical";
        } else if (diffRatio >= getThreshold("high")) {
            return "high";
        } else if (diffRatio >= getThreshold("medium")) {
            return "medium";
        } else {
            return "low";
        }
    }

    public static class SeverityConfig {
        private String name;
        private int level;
        private int alertIntervalSeconds;
        private double threshold;
        private String description;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = level;
        }

        public int getAlertIntervalSeconds() {
            return alertIntervalSeconds;
        }

        public void setAlertIntervalSeconds(int alertIntervalSeconds) {
            this.alertIntervalSeconds = alertIntervalSeconds;
        }

        public double getThreshold() {
            return threshold;
        }

        public void setThreshold(double threshold) {
            this.threshold = threshold;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
