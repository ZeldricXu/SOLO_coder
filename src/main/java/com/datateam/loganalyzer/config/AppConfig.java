package com.datateam.loganalyzer.config;

import com.datateam.loganalyzer.model.NotificationConfig;
import com.datateam.loganalyzer.model.AlertRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppConfig {

    private String appName;
    private String version;
    private String logLevel;
    private String timeZone;

    private ParserConfig parser;
    private AggregatorConfig aggregator;
    private DetectionConfig detection;
    private AlertConfig alert;
    private NotificationConfig notification;
    private Map<String, Object> grokPatterns;
    private List<AlertRule> alertRules;
    private Map<String, Object> templates;

    public AppConfig() {
        this.parser = new ParserConfig();
        this.aggregator = new AggregatorConfig();
        this.detection = new DetectionConfig();
        this.alert = new AlertConfig();
        this.grokPatterns = new HashMap<>();
        this.alertRules = new ArrayList<>();
        this.templates = new HashMap<>();
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public ParserConfig getParser() {
        return parser;
    }

    public void setParser(ParserConfig parser) {
        this.parser = parser;
    }

    public AggregatorConfig getAggregator() {
        return aggregator;
    }

    public void setAggregator(AggregatorConfig aggregator) {
        this.aggregator = aggregator;
    }

    public DetectionConfig getDetection() {
        return detection;
    }

    public void setDetection(DetectionConfig detection) {
        this.detection = detection;
    }

    public AlertConfig getAlert() {
        return alert;
    }

    public void setAlert(AlertConfig alert) {
        this.alert = alert;
    }

    public NotificationConfig getNotification() {
        return notification;
    }

    public void setNotification(NotificationConfig notification) {
        this.notification = notification;
    }

    public Map<String, Object> getGrokPatterns() {
        return grokPatterns;
    }

    public void setGrokPatterns(Map<String, Object> grokPatterns) {
        this.grokPatterns = grokPatterns;
    }

    public List<AlertRule> getAlertRules() {
        return alertRules;
    }

    public void setAlertRules(List<AlertRule> alertRules) {
        this.alertRules = alertRules;
    }

    public Map<String, Object> getTemplates() {
        return templates;
    }

    public void setTemplates(Map<String, Object> templates) {
        this.templates = templates;
    }

    public static class ParserConfig {
        private String defaultFormat;
        private String defaultPattern;
        private String defaultPatternName;
        private String customPatternsDir;
        private boolean enableMultiline = true;
        private String multilineStrategy = "AUTO";
        private int maxLineLength = 1024 * 1024;
        private boolean truncateLongLines = true;

        public String getDefaultFormat() {
            return defaultFormat;
        }

        public void setDefaultFormat(String defaultFormat) {
            this.defaultFormat = defaultFormat;
        }

        public String getDefaultPattern() {
            return defaultPattern;
        }

        public void setDefaultPattern(String defaultPattern) {
            this.defaultPattern = defaultPattern;
        }

        public String getDefaultPatternName() {
            return defaultPatternName;
        }

        public void setDefaultPatternName(String defaultPatternName) {
            this.defaultPatternName = defaultPatternName;
        }

        public String getCustomPatternsDir() {
            return customPatternsDir;
        }

        public void setCustomPatternsDir(String customPatternsDir) {
            this.customPatternsDir = customPatternsDir;
        }

        public boolean isEnableMultiline() {
            return enableMultiline;
        }

        public void setEnableMultiline(boolean enableMultiline) {
            this.enableMultiline = enableMultiline;
        }

        public String getMultilineStrategy() {
            return multilineStrategy;
        }

        public void setMultilineStrategy(String multilineStrategy) {
            this.multilineStrategy = multilineStrategy;
        }

        public int getMaxLineLength() {
            return maxLineLength;
        }

        public void setMaxLineLength(int maxLineLength) {
            this.maxLineLength = maxLineLength;
        }

        public boolean isTruncateLongLines() {
            return truncateLongLines;
        }

        public void setTruncateLongLines(boolean truncateLongLines) {
            this.truncateLongLines = truncateLongLines;
        }
    }

    public static class AggregatorConfig {
        private String defaultGranularity = "1m";
        private String defaultWindow = "5m";
        private int maxCacheSize = 100000;

        public String getDefaultGranularity() {
            return defaultGranularity;
        }

        public void setDefaultGranularity(String defaultGranularity) {
            this.defaultGranularity = defaultGranularity;
        }

        public String getDefaultWindow() {
            return defaultWindow;
        }

        public void setDefaultWindow(String defaultWindow) {
            this.defaultWindow = defaultWindow;
        }

        public int getMaxCacheSize() {
            return maxCacheSize;
        }

        public void setMaxCacheSize(int maxCacheSize) {
            this.maxCacheSize = maxCacheSize;
        }
    }

    public static class DetectionConfig {
        private int baselinePeriodMinutes = 60;
        private double defaultZScoreThreshold = 3.0;
        private int defaultMovingAverageWindow = 10;
        private double defaultMovingAverageSigma = 3.0;
        private int minDataPoints = 30;
        private List<String> enabledAlgorithms;

        public int getBaselinePeriodMinutes() {
            return baselinePeriodMinutes;
        }

        public void setBaselinePeriodMinutes(int baselinePeriodMinutes) {
            this.baselinePeriodMinutes = baselinePeriodMinutes;
        }

        public double getDefaultZScoreThreshold() {
            return defaultZScoreThreshold;
        }

        public void setDefaultZScoreThreshold(double defaultZScoreThreshold) {
            this.defaultZScoreThreshold = defaultZScoreThreshold;
        }

        public int getDefaultMovingAverageWindow() {
            return defaultMovingAverageWindow;
        }

        public void setDefaultMovingAverageWindow(int defaultMovingAverageWindow) {
            this.defaultMovingAverageWindow = defaultMovingAverageWindow;
        }

        public double getDefaultMovingAverageSigma() {
            return defaultMovingAverageSigma;
        }

        public void setDefaultMovingAverageSigma(double defaultMovingAverageSigma) {
            this.defaultMovingAverageSigma = defaultMovingAverageSigma;
        }

        public int getMinDataPoints() {
            return minDataPoints;
        }

        public void setMinDataPoints(int minDataPoints) {
            this.minDataPoints = minDataPoints;
        }

        public List<String> getEnabledAlgorithms() {
            return enabledAlgorithms;
        }

        public void setEnabledAlgorithms(List<String> enabledAlgorithms) {
            this.enabledAlgorithms = enabledAlgorithms;
        }
    }

    public static class AlertConfig {
        private int defaultCooldownSeconds = 300;
        private boolean enableEscalation = true;
        private int escalationDelayMinutes = 10;
        private String defaultSeverity = "WARNING";
        private String alertRulesFile;

        public int getDefaultCooldownSeconds() {
            return defaultCooldownSeconds;
        }

        public void setDefaultCooldownSeconds(int defaultCooldownSeconds) {
            this.defaultCooldownSeconds = defaultCooldownSeconds;
        }

        public boolean isEnableEscalation() {
            return enableEscalation;
        }

        public void setEnableEscalation(boolean enableEscalation) {
            this.enableEscalation = enableEscalation;
        }

        public int getEscalationDelayMinutes() {
            return escalationDelayMinutes;
        }

        public void setEscalationDelayMinutes(int escalationDelayMinutes) {
            this.escalationDelayMinutes = escalationDelayMinutes;
        }

        public String getDefaultSeverity() {
            return defaultSeverity;
        }

        public void setDefaultSeverity(String defaultSeverity) {
            this.defaultSeverity = defaultSeverity;
        }

        public String getAlertRulesFile() {
            return alertRulesFile;
        }

        public void setAlertRulesFile(String alertRulesFile) {
            this.alertRulesFile = alertRulesFile;
        }
    }
}
