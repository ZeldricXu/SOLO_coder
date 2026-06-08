package com.datateam.loganalyzer.alert;

import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.AlertRule;
import com.datateam.loganalyzer.model.TimeSeriesPoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AlertRuleEngine {

    private final List<AlertRule> rules;
    private final Map<String, AlertEvent> activeAlerts;
    private final Map<String, Instant> lastNotificationTimes;
    private final Map<String, Integer> violationCounters;

    public AlertRuleEngine() {
        this.rules = new ArrayList<>();
        this.activeAlerts = new HashMap<>();
        this.lastNotificationTimes = new HashMap<>();
        this.violationCounters = new HashMap<>();
    }

    public void addRule(AlertRule rule) {
        if (rule != null && rule.isEnabled()) {
            this.rules.add(rule);
        }
    }

    public void addRules(List<AlertRule> rules) {
        if (rules != null) {
            for (AlertRule rule : rules) {
                addRule(rule);
            }
        }
    }

    public List<AlertEvent> evaluate(List<TimeSeriesPoint> timeSeries) {
        List<AlertEvent> triggeredAlerts = new ArrayList<>();

        for (AlertRule rule : rules) {
            if (!rule.isEnabled()) continue;

            List<AlertEvent> results = evaluateRule(rule, timeSeries);
            triggeredAlerts.addAll(results);
        }

        checkEscalation(triggeredAlerts);

        return triggeredAlerts;
    }

    private List<AlertEvent> evaluateRule(AlertRule rule, List<TimeSeriesPoint> timeSeries) {
        List<AlertEvent> results = new ArrayList<>();

        if (rule.getType() == AlertRule.RuleType.COMPOSITE) {
            results.addAll(evaluateCompositeRule(rule, timeSeries));
        } else {
            results.addAll(evaluateThresholdRule(rule, timeSeries));
        }

        return results;
    }

    private List<AlertEvent> evaluateThresholdRule(AlertRule rule, List<TimeSeriesPoint> timeSeries) {
        List<AlertEvent> results = new ArrayList<>();
        int consecutiveViolations = 0;

        for (int i = 0; i < timeSeries.size(); i++) {
            TimeSeriesPoint point = timeSeries.get(i);
            double value = getMetricValue(point, rule.getMetric());

            if (rule.evaluate(value)) {
                consecutiveViolations++;

                if (consecutiveViolations >= rule.getMinViolations()) {
                    AlertEvent event = createAlertEvent(rule, point, value);
                    if (shouldSendNotification(event)) {
                        updateNotificationState(event);
                        results.add(event);
                    }
                    activeAlerts.put(rule.getId(), event);
                    consecutiveViolations = 0;
                }
            } else {
                consecutiveViolations = 0;
                clearActiveAlert(rule.getId(), point.getWindowEnd());
            }

            violationCounters.put(rule.getId() + "_" + i, consecutiveViolations);
        }

        return results;
    }

    private List<AlertEvent> evaluateCompositeRule(AlertRule rule, List<TimeSeriesPoint> timeSeries) {
        List<AlertEvent> results = new ArrayList<>();
        List<List<Boolean>> childViolations = new ArrayList<>();

        for (AlertRule child : rule.getChildren()) {
            List<Boolean> violations = new ArrayList<>();
            for (TimeSeriesPoint point : timeSeries) {
                double value = getMetricValue(point, child.getMetric());
                violations.add(child.evaluate(value));
            }
            childViolations.add(violations);
        }

        for (int i = 0; i < timeSeries.size(); i++) {
            List<Boolean> pointViolations = new ArrayList<>();
            for (List<Boolean> cv : childViolations) {
                pointViolations.add(cv.get(i));
            }

            if (rule.evaluateComposite(pointViolations)) {
                TimeSeriesPoint point = timeSeries.get(i);
                double compositeValue = calculateCompositeValue(rule, point);
                AlertEvent event = createAlertEvent(rule, point, compositeValue);

                if (shouldSendNotification(event)) {
                    updateNotificationState(event);
                    results.add(event);
                }
                activeAlerts.put(rule.getId(), event);
            } else {
                clearActiveAlert(rule.getId(), timeSeries.get(i).getWindowEnd());
            }
        }

        return results;
    }

    private double calculateCompositeValue(AlertRule rule, TimeSeriesPoint point) {
        double sum = 0;
        int count = 0;
        for (AlertRule child : rule.getChildren()) {
            sum += getMetricValue(point, child.getMetric());
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    private double getMetricValue(TimeSeriesPoint point, String metric) {
        if (metric == null) return point.getTotalCount();

        switch (metric.toLowerCase()) {
            case "total":
            case "count":
                return point.getTotalCount();
            case "error":
            case "errors":
                return point.getErrorCount();
            case "warn":
            case "warnings":
                return point.getWarnCount();
            case "rate":
            case "rate_per_minute":
                return point.getRatePerMinute();
            case "rate_per_second":
                return point.getRatePerSecond();
            case "error_ratio":
                return point.getTotalCount() > 0 ?
                    (double) point.getErrorCount() / point.getTotalCount() : 0;
            default:
                if (metric.startsWith("service:")) {
                    String serviceName = metric.substring(8);
                    return point.getServiceCounts().getOrDefault(serviceName, 0L);
                }
                if (metric.startsWith("error_type:")) {
                    String errorType = metric.substring(11);
                    return point.getErrorTypeCounts().getOrDefault(errorType, 0L);
                }
                return 0.0;
        }
    }

    private AlertEvent createAlertEvent(AlertRule rule, TimeSeriesPoint point, double value) {
        AlertEvent event = new AlertEvent();
        event.setRuleId(rule.getId());
        event.setRuleName(rule.getName());
        event.setSeverity(rule.getSeverity());
        event.addAffectedPoint(point);

        String description = String.format(
            "Rule '%s' triggered: %s %s %.2f (actual value: %.2f)",
            rule.getName(), rule.getMetric(), rule.getComparison(), rule.getThreshold(), value
        );
        event.setDescription(description);

        event.addDetail(String.format("Metric: %s", rule.getMetric()));
        event.addDetail(String.format("Threshold: %s %.2f", rule.getComparison(), rule.getThreshold()));
        event.addDetail(String.format("Observed: %.2f", value));
        event.addDetail(String.format("Window: %s -> %s", point.getWindowStart(), point.getWindowEnd()));

        AlertEvent existing = activeAlerts.get(rule.getId());
        if (existing != null && existing.isActive()) {
            event.setTriggeredAt(existing.getTriggeredAt());
            event.setEscalationCount(existing.getEscalationCount());
            if (existing.getSeverity().isHigherThan(rule.getSeverity())) {
                event.setSeverity(existing.getSeverity());
            }
        }

        return event;
    }

    private boolean shouldSendNotification(AlertEvent event) {
        Instant lastNotified = lastNotificationTimes.get(event.getRuleId());
        if (lastNotified == null) {
            return true;
        }

        AlertRule rule = getRule(event.getRuleId());
        if (rule == null) {
            return true;
        }

        long cooldownSeconds = rule.getCooldownSeconds();
        Instant now = Instant.now();
        long elapsed = java.time.Duration.between(lastNotified, now).getSeconds();

        return elapsed >= cooldownSeconds;
    }

    private void updateNotificationState(AlertEvent event) {
        event.setLastNotifiedAt(Instant.now());
        lastNotificationTimes.put(event.getRuleId(), event.getLastNotifiedAt());
    }

    private void clearActiveAlert(String ruleId, Instant recoveryTime) {
        AlertEvent active = activeAlerts.get(ruleId);
        if (active != null && active.isActive()) {
            active.setRecoveredAt(recoveryTime);
            active.setActive(false);
        }
        activeAlerts.remove(ruleId);
    }

    private void checkEscalation(List<AlertEvent> currentTriggered) {
        Instant now = Instant.now();

        for (Map.Entry<String, AlertEvent> entry : activeAlerts.entrySet()) {
            AlertEvent event = entry.getValue();
            AlertRule rule = getRule(entry.getKey());

            if (rule == null || !event.isActive()) continue;

            long durationMinutes = event.getDurationMinutes();
            int expectedEscalations = (int) (durationMinutes / rule.getEscalationMinutes());

            while (event.getEscalationCount() < expectedEscalations) {
                event.incrementEscalation();

                if (shouldSendNotification(event)) {
                    AlertEvent escalatedEvent = new AlertEvent();
                    escalatedEvent.setRuleId(event.getRuleId());
                    escalatedEvent.setRuleName(event.getRuleName() + " (ESCALATED)");
                    escalatedEvent.setSeverity(event.getSeverity());
                    escalatedEvent.setTriggeredAt(event.getTriggeredAt());
                    escalatedEvent.setEscalationCount(event.getEscalationCount());
                    escalatedEvent.setDescription(String.format(
                        "ALERT ESCALATION: '%s' has been active for %d minutes. Severity upgraded to %s",
                        event.getRuleName(), durationMinutes, event.getSeverity()
                    ));
                    escalatedEvent.addDetail(String.format("Duration: %d minutes", durationMinutes));
                    escalatedEvent.addDetail(String.format("Escalation level: %d", event.getEscalationCount()));
                    escalatedEvent.addDetail(String.format("Current severity: %s", event.getSeverity()));

                    updateNotificationState(escalatedEvent);
                    currentTriggered.add(escalatedEvent);
                }
            }
        }
    }

    private AlertRule getRule(String ruleId) {
        for (AlertRule rule : rules) {
            if (rule.getId().equals(ruleId)) {
                return rule;
            }
        }
        return null;
    }

    public List<AlertRule> getRules() {
        return rules;
    }

    public Map<String, AlertEvent> getActiveAlerts() {
        return activeAlerts;
    }

    public void clearState() {
        activeAlerts.clear();
        lastNotificationTimes.clear();
        violationCounters.clear();
    }
}
