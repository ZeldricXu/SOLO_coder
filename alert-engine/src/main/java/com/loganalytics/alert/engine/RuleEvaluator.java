package com.loganalytics.alert.engine;

import com.loganalytics.alert.config.AlertEngineConfig;
import com.loganalytics.alert.notification.NotificationManager;
import com.loganalytics.alert.state.AlertStateManager;
import com.loganalytics.common.model.*;
import com.loganalytics.metrics.window.WindowedAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class RuleEvaluator {
    private static final Logger log = LoggerFactory.getLogger(RuleEvaluator.class);
    private final AlertEngineConfig config;
    private final AlertStateManager stateManager;
    private final NotificationManager notificationManager;
    private final WindowedAggregator metricsAggregator;
    private final Map<String, AlertRule> rules = new ConcurrentHashMap<>();
    private final Map<String, Pattern> keywordPatternCache = new ConcurrentHashMap<>();

    public RuleEvaluator(AlertEngineConfig config, AlertStateManager stateManager,
                         NotificationManager notificationManager, WindowedAggregator metricsAggregator) {
        this.config = config;
        this.stateManager = stateManager;
        this.notificationManager = notificationManager;
        this.metricsAggregator = metricsAggregator;
    }

    public void addRule(AlertRule rule) {
        rules.put(rule.getId(), rule);
        if (rule.getKeyword() != null) {
            keywordPatternCache.put(rule.getId(), Pattern.compile(rule.getKeyword(), Pattern.CASE_INSENSITIVE));
        }
    }

    public void removeRule(String ruleId) {
        rules.remove(ruleId);
        keywordPatternCache.remove(ruleId);
    }

    public List<AlertRule> getRules() {
        return new ArrayList<>(rules.values());
    }

    public void evaluateAllRules() {
        Instant now = Instant.now();
        log.debug("Evaluating {} rules at {}", rules.size(), now);

        for (AlertRule rule : rules.values()) {
            if (!rule.isEnabled()) {
                continue;
            }
            try {
                evaluateRule(rule, now);
            } catch (Exception e) {
                log.error("Error evaluating rule {}: {}", rule.getId(), rule.getName(), e);
            }
        }
    }

    private void evaluateRule(AlertRule rule, Instant now) {
        Duration window = rule.getEvaluationWindow() != null
                ? rule.getEvaluationWindow() : Duration.ofMinutes(5);

        switch (rule.getConditionType()) {
            case METRIC_THRESHOLD -> evaluateMetricThreshold(rule, now, window);
            case PATTERN_FREQUENCY -> evaluatePatternFrequency(rule, now, window);
            case KEYWORD_MATCH -> evaluateKeywordMatch(rule, now, window);
            case ANOMALY_TYPE -> evaluateAnomalyType(rule, now, window);
            case ERROR_RATE -> evaluateErrorRate(rule, now, window);
        }
    }

    private void evaluateMetricThreshold(AlertRule rule, Instant now, Duration window) {
        String metricName = rule.getMetricName();
        if (metricName == null) return;

        List<String> services = rule.getServiceFilter();
        if (services == null || services.isEmpty()) {
            services = List.of("*");
        }

        for (String service : services) {
            double currentValue = getCurrentMetricValue(metricName, service, window);
            boolean conditionMet = evaluateCondition(currentValue, rule.getOperator(), rule.getThreshold());

            Map<String, Object> labels = new HashMap<>();
            labels.put("metric", metricName);
            labels.put("value", currentValue);
            labels.put("threshold", rule.getThreshold());

            if (conditionMet) {
                String summary = String.format("Metric %s %s %.2f (current: %.2f)",
                        metricName, rule.getOperator(), rule.getThreshold(), currentValue);
                String description = String.format("Service: %s, Window: %s, Current value %.2f %s threshold %.2f",
                        service, window, currentValue, rule.getOperator(), rule.getThreshold());

                stateManager.checkAndCreateAlert(rule, service, labels, summary, description)
                        .ifPresent(alert -> sendNotificationIfNeeded(alert, rule));
            } else {
                stateManager.conditionResolved(rule, service, labels);
            }
        }
    }

    private void evaluatePatternFrequency(AlertRule rule, Instant now, Duration window) {
        List<String> services = rule.getServiceFilter();
        if (services == null || services.isEmpty()) {
            services = List.of("*");
        }

        for (String service : services) {
            double currentFreq = getPatternFrequency(service, window, rule.getLevelFilter());
            double baseline = getBaselineFrequency(service, window);
            double threshold = baseline * rule.getThreshold();

            boolean conditionMet = currentFreq > threshold;

            Map<String, Object> labels = new HashMap<>();
            labels.put("frequency", currentFreq);
            labels.put("baseline", baseline);
            labels.put("threshold", threshold);

            if (conditionMet) {
                String summary = String.format("Pattern frequency exceeded baseline: %.2f > %.2f (%.1fx)",
                        currentFreq, baseline, rule.getThreshold());
                String description = String.format("Service: %s, Window: %s, Current frequency %.2f, Baseline %.2f, Threshold %.2f",
                        service, window, currentFreq, baseline, threshold);

                stateManager.checkAndCreateAlert(rule, service, labels, summary, description)
                        .ifPresent(alert -> sendNotificationIfNeeded(alert, rule));
            } else {
                stateManager.conditionResolved(rule, service, labels);
            }
        }
    }

    private void evaluateKeywordMatch(AlertRule rule, Instant now, Duration window) {
        if (rule.getKeyword() == null) return;
        Pattern pattern = keywordPatternCache.get(rule.getId());

        List<String> services = rule.getServiceFilter();
        if (services == null || services.isEmpty()) {
            services = List.of("*");
        }

        for (String service : services) {
            long matchCount = countKeywordMatches(pattern, service, window, rule.getLevelFilter());
            boolean conditionMet = matchCount > 0;

            Map<String, Object> labels = new HashMap<>();
            labels.put("keyword", rule.getKeyword());
            labels.put("matchCount", matchCount);

            if (conditionMet) {
                String summary = String.format("Keyword '%s' matched %d times", rule.getKeyword(), matchCount);
                String description = String.format("Service: %s, Window: %s, Pattern: '%s' matched %d times",
                        service, window, rule.getKeyword(), matchCount);

                stateManager.checkAndCreateAlert(rule, service, labels, summary, description)
                        .ifPresent(alert -> sendNotificationIfNeeded(alert, rule));
            } else {
                stateManager.conditionResolved(rule, service, labels);
            }
        }
    }

    private void evaluateAnomalyType(AlertRule rule, Instant now, Duration window) {
        if (rule.getAnomalyType() == null) return;

        List<String> services = rule.getServiceFilter();
        if (services == null || services.isEmpty()) {
            services = List.of("*");
        }

        for (String service : services) {
            long anomalyCount = countAnomalies(rule.getAnomalyType(), service, window);
            boolean conditionMet = anomalyCount > 0;

            Map<String, Object> labels = new HashMap<>();
            labels.put("anomalyType", rule.getAnomalyType().name());
            labels.put("count", anomalyCount);

            if (conditionMet) {
                String summary = String.format("%s anomaly detected: %d occurrences", rule.getAnomalyType(), anomalyCount);
                String description = String.format("Service: %s, Window: %s, Anomaly type %s detected %d times",
                        service, window, rule.getAnomalyType(), anomalyCount);

                stateManager.checkAndCreateAlert(rule, service, labels, summary, description)
                        .ifPresent(alert -> sendNotificationIfNeeded(alert, rule));
            } else {
                stateManager.conditionResolved(rule, service, labels);
            }
        }
    }

    private void evaluateErrorRate(AlertRule rule, Instant now, Duration window) {
        List<String> services = rule.getServiceFilter();
        if (services == null || services.isEmpty()) {
            services = List.of("*");
        }

        for (String service : services) {
            double errorRate = getErrorRate(service, window);
            boolean conditionMet = evaluateCondition(errorRate, rule.getOperator(), rule.getThreshold());

            Map<String, Object> labels = new HashMap<>();
            labels.put("errorRate", errorRate);
            labels.put("threshold", rule.getThreshold());

            if (conditionMet) {
                String summary = String.format("Error rate %.2f%% %s threshold %.2f%%",
                        errorRate * 100, rule.getOperator(), rule.getThreshold() * 100);
                String description = String.format("Service: %s, Window: %s, Error rate: %.2f%%, Threshold: %.2f%%",
                        service, window, errorRate * 100, rule.getThreshold() * 100);

                stateManager.checkAndCreateAlert(rule, service, labels, summary, description)
                        .ifPresent(alert -> sendNotificationIfNeeded(alert, rule));
            } else {
                stateManager.conditionResolved(rule, service, labels);
            }
        }
    }

    private boolean evaluateCondition(double value, AlertRule.Operator operator, double threshold) {
        return switch (operator) {
            case GT -> value > threshold;
            case GTE -> value >= threshold;
            case LT -> value < threshold;
            case LTE -> value <= threshold;
            case EQ -> Math.abs(value - threshold) < 0.0001;
            case NEQ -> Math.abs(value - threshold) >= 0.0001;
            default -> false;
        };
    }

    private void sendNotificationIfNeeded(Alert alert, AlertRule rule) {
        if (stateManager.shouldSendNotification(alert)) {
            notificationManager.sendNotifications(alert, rule);
            stateManager.markNotificationSent(alert);
        }
    }

    private double getCurrentMetricValue(String metricName, String service, Duration window) {
        if (metricsAggregator != null) {
            return metricsAggregator.getCurrentMetricValue(metricName, service, window);
        }
        return 0.0;
    }

    private double getPatternFrequency(String service, Duration window, List<LogLevel> levelFilter) {
        if (metricsAggregator != null) {
            return metricsAggregator.getPatternFrequency(service, window, levelFilter);
        }
        return 0.0;
    }

    private double getBaselineFrequency(String service, Duration window) {
        if (metricsAggregator != null) {
            return metricsAggregator.getBaselineFrequency(service, window);
        }
        return 1.0;
    }

    private long countKeywordMatches(Pattern pattern, String service, Duration window, List<LogLevel> levelFilter) {
        return 0;
    }

    private long countAnomalies(AnomalyEvent.AnomalyType anomalyType, String service, Duration window) {
        return 0;
    }

    private double getErrorRate(String service, Duration window) {
        if (metricsAggregator != null) {
            return metricsAggregator.getErrorRate(service, window);
        }
        return 0.0;
    }

    public AlertStateManager getStateManager() {
        return stateManager;
    }
}
