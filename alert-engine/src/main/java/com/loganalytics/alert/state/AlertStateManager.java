package com.loganalytics.alert.state;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.loganalytics.alert.config.AlertEngineConfig;
import com.loganalytics.common.model.Alert;
import com.loganalytics.common.model.AlertRule;
import com.loganalytics.common.util.IdUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class AlertStateManager {
    private final AlertEngineConfig config;
    private final Map<String, Alert> activeAlerts = new ConcurrentHashMap<>();
    private final Cache<String, Instant> cooldownCache;
    private final Map<String, Instant> firstFiringTime = new ConcurrentHashMap<>();

    public AlertStateManager(AlertEngineConfig config) {
        this.config = config;
        this.cooldownCache = Caffeine.newBuilder()
                .expireAfterWrite(config.getDefaultCooldownPeriod())
                .maximumSize(10000)
                .build();
    }

    public Optional<Alert> checkAndCreateAlert(AlertRule rule, String serviceName,
                                                Map<String, Object> labels, String summary, String description) {
        String alertKey = generateAlertKey(rule, serviceName, labels);

        if (isInCooldown(alertKey)) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        firstFiringTime.putIfAbsent(alertKey, now);

        Duration firingDuration = Duration.between(firstFiringTime.get(alertKey), now);
        int minFiringMinutes = rule.getMinFiringDurationMinutes() > 0
                ? rule.getMinFiringDurationMinutes() : 1;

        if (firingDuration.toMinutes() < minFiringMinutes) {
            return Optional.empty();
        }

        Alert existingAlert = activeAlerts.get(alertKey);
        if (existingAlert != null) {
            existingAlert.setUpdatedAt(now);
            checkEscalation(existingAlert, rule);
            return Optional.of(existingAlert);
        }

        Alert alert = createNewAlert(rule, serviceName, labels, summary, description);
        activeAlerts.put(alertKey, alert);
        firstFiringTime.remove(alertKey);

        return Optional.of(alert);
    }

    public void resolveAlert(String alertId) {
        activeAlerts.entrySet().stream()
                .filter(e -> e.getValue().getId().equals(alertId))
                .findFirst()
                .ifPresent(e -> {
                    Alert alert = e.getValue();
                    alert.setStatus(Alert.AlertStatus.RESOLVED);
                    alert.setResolvedAt(Instant.now());
                    activeAlerts.remove(e.getKey());
                    markCooldown(e.getKey());
                });
    }

    public void acknowledgeAlert(String alertId, String acknowledgedBy) {
        activeAlerts.values().stream()
                .filter(a -> a.getId().equals(alertId))
                .findFirst()
                .ifPresent(alert -> {
                    alert.setStatus(Alert.AlertStatus.ACKNOWLEDGED);
                    alert.setAcknowledgedBy(acknowledgedBy);
                    alert.setAcknowledgedAt(Instant.now());
                });
    }

    private void checkEscalation(Alert alert, AlertRule rule) {
        if (alert.getStatus() != Alert.AlertStatus.FIRING) {
            return;
        }

        Duration escalationDelay = rule.getEscalationDelay() != null
                ? rule.getEscalationDelay() : config.getDefaultEscalationDelay();
        int maxLevel = rule.getMaxEscalationLevel() > 0 ? rule.getMaxEscalationLevel() : 3;

        Duration sinceCreation = Duration.between(alert.getCreatedAt(), Instant.now());
        int expectedLevel = (int) (sinceCreation.toMinutes() / escalationDelay.toMinutes()) + 1;
        expectedLevel = Math.min(expectedLevel, maxLevel);

        if (expectedLevel > alert.getEscalationLevel()) {
            alert.setEscalationLevel(expectedLevel);
            alert.setStatus(Alert.AlertStatus.ESCALATED);
            alert.setUpdatedAt(Instant.now());
        }
    }

    public boolean shouldSendNotification(Alert alert) {
        Instant now = Instant.now();
        Duration minInterval = Duration.ofMinutes(5);

        if (alert.getLastNotificationSent() == null) {
            return true;
        }

        Duration sinceLast = Duration.between(alert.getLastNotificationSent(), now);
        return sinceLast.compareTo(minInterval) >= 0;
    }

    public void markNotificationSent(Alert alert) {
        alert.setLastNotificationSent(Instant.now());
        alert.setNotificationCount(alert.getNotificationCount() + 1);
    }

    private boolean isInCooldown(String alertKey) {
        return cooldownCache.getIfPresent(alertKey) != null;
    }

    private void markCooldown(String alertKey) {
        cooldownCache.put(alertKey, Instant.now());
    }

    private String generateAlertKey(AlertRule rule, String serviceName, Map<String, Object> labels) {
        StringBuilder sb = new StringBuilder(rule.getId());
        if (serviceName != null) {
            sb.append(":").append(serviceName);
        }
        if (labels != null && !labels.isEmpty()) {
            String labelPart = labels.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(","));
            sb.append(":").append(labelPart);
        }
        return sb.toString();
    }

    private Alert createNewAlert(AlertRule rule, String serviceName,
                                  Map<String, Object> labels, String summary, String description) {
        Alert alert = new Alert();
        alert.setId(IdUtils.generateId("alert"));
        alert.setRuleId(rule.getId());
        alert.setRuleName(rule.getName());
        alert.setStatus(Alert.AlertStatus.FIRING);
        alert.setSeverity(rule.getSeverity());
        alert.setCreatedAt(Instant.now());
        alert.setUpdatedAt(Instant.now());
        alert.setServiceName(serviceName);
        alert.setSummary(summary);
        alert.setDescription(description);
        alert.setLabels(labels);
        alert.setNotificationChannels(rule.getNotificationChannels().stream()
                .map(Enum::name)
                .collect(Collectors.toList()));
        alert.setEscalationLevel(1);
        alert.setNotificationCount(0);
        return alert;
    }

    public List<Alert> getActiveAlerts() {
        return new ArrayList<>(activeAlerts.values());
    }

    public void conditionResolved(AlertRule rule, String serviceName, Map<String, Object> labels) {
        String alertKey = generateAlertKey(rule, serviceName, labels);
        firstFiringTime.remove(alertKey);
    }
}
