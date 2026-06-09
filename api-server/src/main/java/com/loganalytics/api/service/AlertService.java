package com.loganalytics.alert;

import com.loganalytics.alert.engine.RuleEvaluator;
import com.loganalytics.common.model.Alert;
import com.loganalytics.common.model.AlertRule;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AlertService {
    private final Map<String, Alert> alerts = new ConcurrentHashMap<>();
    private RuleEvaluator ruleEvaluator;

    public AlertService() {
        loadMockAlerts();
    }

    private void loadMockAlerts() {
        Alert alert1 = new Alert();
        alert1.setId("alert-001");
        alert1.setRuleId("rule-001");
        alert1.setRuleName("High Error Rate");
        alert1.setStatus(Alert.AlertStatus.FIRING);
        alert1.setSeverity(com.loganalytics.common.model.AnomalyEvent.Severity.HIGH);
        alert1.setCreatedAt(Instant.now().minusMinutes(15));
        alert1.setUpdatedAt(Instant.now());
        alert1.setServiceName("payment-service");
        alert1.setSummary("Error rate exceeded 5% threshold");
        alert1.setDescription("Payment service error rate is currently 8.2%");
        alert1.setLabels(Map.of("errorRate", 0.082, "threshold", 0.05));
        alert1.setNotificationChannels(List.of("EMAIL", "SLACK"));
        alert1.setEscalationLevel(2);
        alert1.setNotificationCount(3);
        alerts.put(alert1.getId(), alert1);

        Alert alert2 = new Alert();
        alert2.setId("alert-002");
        alert2.setRuleId("rule-002");
        alert2.setRuleName("Connection Timeout Spike");
        alert2.setStatus(Alert.AlertStatus.ACKNOWLEDGED);
        alert2.setSeverity(com.loganalytics.common.model.AnomalyEvent.Severity.MEDIUM);
        alert2.setCreatedAt(Instant.now().minusMinutes(45));
        alert2.setUpdatedAt(Instant.now().minusMinutes(20));
        alert2.setServiceName("gateway-service");
        alert2.setSummary("Connection timeout frequency increased");
        alert2.setDescription("Gateway service experiencing 3x normal connection timeout rate");
        alert2.setLabels(Map.of("frequency", 45.0, "baseline", 15.0));
        alert2.setNotificationChannels(List.of("SLACK"));
        alert2.setEscalationLevel(1);
        alert2.setNotificationCount(2);
        alert2.setAcknowledgedBy("oncall-engineer@example.com");
        alert2.setAcknowledgedAt(Instant.now().minusMinutes(20));
        alerts.put(alert2.getId(), alert2);

        Alert alert3 = new Alert();
        alert3.setId("alert-003");
        alert3.setRuleId("rule-003");
        alert3.setRuleName("New Pattern Detected");
        alert3.setStatus(Alert.AlertStatus.PENDING);
        alert3.setSeverity(com.loganalytics.common.model.AnomalyEvent.Severity.LOW);
        alert3.setCreatedAt(Instant.now().minusMinutes(5));
        alert3.setUpdatedAt(Instant.now().minusMinutes(5));
        alert3.setServiceName("user-service");
        alert3.setSummary("New log pattern detected");
        alert3.setDescription("Previously unseen log pattern appeared in user service");
        alert3.setLabels(Map.of("patternId", "new-pattern-123));
        alert3.setNotificationChannels(List.of("SLACK"));
        alert3.setEscalationLevel(1);
        alert3.setNotificationCount(1);
        alerts.put(alert3.getId(), alert3);
    }

    public Map<String, Object> getAlerts(String status, String severity, String service,
                                         int page, int pageSize) {
        List<Alert> filtered = alerts.values().stream()
                .filter(a -> status == null || a.getStatus().name().equalsIgnoreCase(status))
                .filter(a -> severity == null || a.getSeverity().name().equalsIgnoreCase(severity))
                .filter(a -> service == null || service.equals("*") || a.getServiceName().contains(service))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .collect(Collectors.toList());

        int start = page * pageSize;
        int end = Math.min(start + pageSize, filtered.size());
        List<Alert> paginated = start < filtered.size()
                ? filtered.subList(start, end)
                : Collections.emptyList();

        Map<String, Object> result = new HashMap<>();
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("total", filtered.size());
        result.put("alerts", paginated.stream().map(this::toMap).collect(Collectors.toList()));

        return result;
    }

    public Optional<Map<String, Object>> getAlertById(String id) {
        return Optional.ofNullable(alerts.get(id)).map(this::toDetailedMap);
    }

    public Map<String, Object> acknowledgeAlert(String id, String acknowledgedBy) {
        Alert alert = alerts.get(id);
        if (alert == null) {
            return Map.of("error", "Alert not found");
        }
        alert.setStatus(Alert.AlertStatus.ACKNOWLEDGED);
        alert.setAcknowledgedBy(acknowledgedBy);
        alert.setAcknowledgedAt(Instant.now());
        alert.setUpdatedAt(Instant.now());
        return toMap(alert);
    }

    public Map<String, Object> resolveAlert(String id) {
        Alert alert = alerts.get(id);
        if (alert == null) {
            return Map.of("error", "Alert not found");
        }
        alert.setStatus(Alert.AlertStatus.RESOLVED);
        alert.setResolvedAt(Instant.now());
        alert.setUpdatedAt(Instant.now());
        return toMap(alert);
    }

    public Map<String, Object> getAlertStats() {
        Map<String, Long> statusCounts = alerts.values().stream()
                .collect(Collectors.groupingBy(
                        a -> a.getStatus().name(),
                        Collectors.counting()
                ));

        Map<String, Long> severityCounts = alerts.values().stream()
                .collect(Collectors.groupingBy(
                        a -> a.getSeverity().name(),
                        Collectors.counting()
                ));

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", alerts.size());
        stats.put("byStatus", statusCounts);
        stats.put("bySeverity", severityCounts);
        stats.put("firing", alerts.values().stream()
                .filter(a -> a.getStatus() == Alert.AlertStatus.FIRING).count());
        stats.put("acknowledged", alerts.values().stream()
                .filter(a -> a.getStatus() == Alert.AlertStatus.ACKNOWLEDGED).count());

        return stats;
    }

    public List<AlertRule> getRules() {
        if (ruleEvaluator != null) {
            return ruleEvaluator.getRules();
        }
        return Collections.emptyList();
    }

    public void setRuleEvaluator(RuleEvaluator ruleEvaluator) {
        this.ruleEvaluator = ruleEvaluator;
    }

    private Map<String, Object> toMap(Alert alert) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", alert.getId());
        map.put("ruleId", alert.getRuleId());
        map.put("ruleName", alert.getRuleName());
        map.put("status", alert.getStatus().name());
        map.put("severity", alert.getSeverity().name());
        map.put("createdAt", alert.getCreatedAt().toString());
        map.put("updatedAt", alert.getUpdatedAt().toString());
        map.put("serviceName", alert.getServiceName());
        map.put("summary", alert.getSummary());
        map.put("labels", alert.getLabels());
        map.put("escalationLevel", alert.getEscalationLevel());
        map.put("notificationCount", alert.getNotificationCount());
        return map;
    }

    private Map<String, Object> toDetailedMap(Alert alert) {
        Map<String, Object> map = toMap(alert);
        map.put("description", alert.getDescription());
        map.put("notificationChannels", alert.getNotificationChannels());
        map.put("acknowledgedBy", alert.getAcknowledgedBy());
        map.put("acknowledgedAt", alert.getAcknowledgedAt() != null ?
                alert.getAcknowledgedAt().toString() : null);
        map.put("resolvedAt", alert.getResolvedAt() != null ?
                alert.getResolvedAt().toString() : null);
        map.put("lastNotificationSent", alert.getLastNotificationSent() != null ?
                alert.getLastNotificationSent().toString() : null);
        return map;
    }
}
