package com.datapipeline.monitoring.alert;

import com.datapipeline.common.model.StatisticsSnapshot;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
public class AlertRuleEngine {

    private final List<AlertRule> rules = new CopyOnWriteArrayList<>();
    private final Map<String, AlertEvent> activeAlerts = new ConcurrentHashMap<>();
    private final List<Consumer<AlertEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<MetricValue>> metricHistory = new ConcurrentHashMap<>();
    private final int maxHistorySize = 100;

    public void addRule(AlertRule rule) {
        rules.add(rule);
        log.info("Alert rule added: id={}, metric={}", rule.getRuleId(), rule.getMetricName());
    }

    public void removeRule(String ruleId) {
        rules.removeIf(r -> ruleId.equals(r.getRuleId()));
        log.info("Alert rule removed: id={}", ruleId);
    }

    public void registerListener(Consumer<AlertEvent> listener) {
        listeners.add(listener);
    }

    public void evaluate(StatisticsSnapshot snapshot) {
        Map<String, Number> metrics = snapshot.getMetrics();
        for (Map.Entry<String, Number> entry : metrics.entrySet()) {
            recordMetric(entry.getKey(), entry.getValue(), snapshot.getTimestamp().toEpochMilli());
        }

        for (AlertRule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }
            evaluateRule(rule, metrics);
        }
    }

    private void recordMetric(String name, Number value, long timestamp) {
        Deque<MetricValue> history = metricHistory.computeIfAbsent(name, k -> new ArrayDeque<>(maxHistorySize));
        synchronized (history) {
            if (history.size() >= maxHistorySize) {
                history.pollFirst();
            }
            history.addLast(new MetricValue(value, timestamp));
        }
    }

    private void evaluateRule(AlertRule rule, Map<String, Number> metrics) {
        Number currentValue = metrics.get(rule.getMetricName());
        if (currentValue == null) {
            return;
        }

        boolean thresholdBreached = compare(currentValue, rule.getOperator(), rule.getThreshold());
        String alertKey = rule.getRuleId();

        if (thresholdBreached) {
            if (!activeAlerts.containsKey(alertKey)) {
                AlertEvent event = AlertEvent.builder()
                        .alertId(UUID.randomUUID().toString())
                        .ruleId(rule.getRuleId())
                        .metricName(rule.getMetricName())
                        .value(currentValue)
                        .threshold(rule.getThreshold())
                        .severity(rule.getSeverity())
                        .status(AlertEvent.Status.FIRING)
                        .labels(rule.getLabels())
                        .message(String.format("Metric %s breached threshold: %s %s %s",
                                rule.getMetricName(), currentValue, rule.getOperator(), rule.getThreshold()))
                        .startedAt(java.time.Instant.now())
                        .build();
                activeAlerts.put(alertKey, event);
                notifyListeners(event);
                log.warn("Alert fired: rule={}, metric={}, value={}",
                        rule.getRuleId(), rule.getMetricName(), currentValue);
            }
        } else {
            AlertEvent existing = activeAlerts.remove(alertKey);
            if (existing != null) {
                AlertEvent resolved = AlertEvent.builder()
                        .alertId(existing.getAlertId())
                        .ruleId(existing.getRuleId())
                        .metricName(existing.getMetricName())
                        .value(currentValue)
                        .threshold(existing.getThreshold())
                        .severity(existing.getSeverity())
                        .status(AlertEvent.Status.RESOLVED)
                        .labels(existing.getLabels())
                        .message("Alert resolved: " + existing.getMessage())
                        .startedAt(existing.getStartedAt())
                        .resolvedAt(java.time.Instant.now())
                        .durationMs(System.currentTimeMillis() - existing.getStartedAt().toEpochMilli())
                        .build();
                notifyListeners(resolved);
                log.info("Alert resolved: rule={}, metric={}", rule.getRuleId(), rule.getMetricName());
            }
        }
    }

    private boolean compare(Number value, AlertRule.Operator op, Number threshold) {
        double v = value.doubleValue();
        double t = threshold.doubleValue();
        return switch (op) {
            case GT -> v > t;
            case LT -> v < t;
            case GTE -> v >= t;
            case LTE -> v <= t;
            case EQ -> v == t;
            case NEQ -> v != t;
        };
    }

    private void notifyListeners(AlertEvent event) {
        for (Consumer<AlertEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.error("Alert listener failed", e);
            }
        }
    }

    public Map<String, AlertEvent> getActiveAlerts() {
        return new HashMap<>(activeAlerts);
    }

    public List<AlertRule> getRules() {
        return new ArrayList<>(rules);
    }

    public record MetricValue(Number value, long timestamp) {}

}
