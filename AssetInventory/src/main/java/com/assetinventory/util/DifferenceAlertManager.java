package com.assetinventory.util;

import com.assetinventory.config.AlertConfig;
import com.assetinventory.entity.InventoryDifference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class DifferenceAlertManager {

    private static final Logger logger = LoggerFactory.getLogger(DifferenceAlertManager.class);

    private final AlertConfig alertConfig;

    public enum SeverityLevel {
        CRITICAL("critical"),
        HIGH("high"),
        MEDIUM("medium"),
        LOW("low");

        private final String configKey;

        SeverityLevel(String configKey) {
            this.configKey = configKey;
        }

        public String getConfigKey() {
            return configKey;
        }

        public static SeverityLevel fromString(String severity) {
            if (severity == null) {
                return LOW;
            }
            try {
                return SeverityLevel.valueOf(severity.toUpperCase());
            } catch (IllegalArgumentException e) {
                return LOW;
            }
        }
    }

    public static class AlertRecord {
        private final String diffId;
        private final SeverityLevel severity;
        private final String severityName;
        private final String message;
        private final Instant createdAt;
        private final int alertCount;
        private final Instant lastAlertAt;
        private final long alertIntervalMs;

        public AlertRecord(String diffId, SeverityLevel severity, String severityName,
                          String message, Instant createdAt, int alertCount,
                          Instant lastAlertAt, long alertIntervalMs) {
            this.diffId = diffId;
            this.severity = severity;
            this.severityName = severityName;
            this.message = message;
            this.createdAt = createdAt;
            this.alertCount = alertCount;
            this.lastAlertAt = lastAlertAt;
            this.alertIntervalMs = alertIntervalMs;
        }

        public String getDiffId() {
            return diffId;
        }

        public SeverityLevel getSeverity() {
            return severity;
        }

        public String getSeverityName() {
            return severityName;
        }

        public String getMessage() {
            return message;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public int getAlertCount() {
            return alertCount;
        }

        public Instant getLastAlertAt() {
            return lastAlertAt;
        }

        public long getAlertIntervalMs() {
            return alertIntervalMs;
        }
    }

    private static class AlertState {
        final InventoryDifference difference;
        final SeverityLevel severity;
        final String severityName;
        final long alertIntervalMs;
        final AtomicInteger alertCount = new AtomicInteger(0);
        volatile Instant lastAlertAt = null;

        AlertState(InventoryDifference difference, SeverityLevel severity,
                  String severityName, long alertIntervalMs) {
            this.difference = difference;
            this.severity = severity;
            this.severityName = severityName;
            this.alertIntervalMs = alertIntervalMs;
        }
    }

    private final Map<String, AlertState> alertStates = new ConcurrentHashMap<>();
    private final List<AlertRecord> sentAlerts = new ArrayList<>();

    @Autowired
    public DifferenceAlertManager(AlertConfig alertConfig) {
        this.alertConfig = alertConfig;
    }

    public SeverityLevel determineSeverity(InventoryDifference diff) {
        if (!alertConfig.isEnabled()) {
            logger.warn("Alert manager is disabled, returning LOW severity");
            return SeverityLevel.LOW;
        }

        int diffValue = diff.getDiffValue();
        int systemValue = diff.getDiffSystem();

        if (systemValue == 0) {
            return diffValue > 0 ? SeverityLevel.CRITICAL : SeverityLevel.LOW;
        }

        double diffRatio = (double) diffValue / systemValue;
        String severityKey = alertConfig.determineSeverityByRatio(diffRatio);

        logger.debug("Determined severity for diff {}: ratio={}, severity={}",
                diff.getDiffId(), diffRatio, severityKey);

        return SeverityLevel.fromString(severityKey);
    }

    public boolean shouldTriggerAlert(InventoryDifference diff) {
        if (!alertConfig.isEnabled()) {
            return false;
        }

        String diffId = diff.getDiffId();
        AlertState state = alertStates.get(diffId);

        if (state == null) {
            return true;
        }

        if (state.lastAlertAt == null) {
            return true;
        }

        long timeSinceLastAlert = System.currentTimeMillis() - state.lastAlertAt.toEpochMilli();
        boolean shouldTrigger = timeSinceLastAlert >= state.alertIntervalMs;

        logger.debug("Should trigger alert for diff {}: timeSinceLastAlert={}ms, interval={}ms, shouldTrigger={}",
                diffId, timeSinceLastAlert, state.alertIntervalMs, shouldTrigger);

        return shouldTrigger;
    }

    public AlertRecord triggerAlert(InventoryDifference diff) {
        if (!alertConfig.isEnabled()) {
            logger.warn("Alert manager is disabled, skipping alert for diff: {}", diff.getDiffId());
            return null;
        }

        String diffId = diff.getDiffId();

        SeverityLevel severity = determineSeverity(diff);
        String severityKey = severity.getConfigKey();
        String severityName = alertConfig.getName(severityKey);
        long alertIntervalMs = alertConfig.getAlertIntervalMs(severityKey);

        AlertState state = alertStates.computeIfAbsent(diffId,
                id -> new AlertState(diff, severity, severityName, alertIntervalMs));

        if (!shouldTriggerAlert(diff)) {
            logger.debug("Alert not triggered for diff {}: interval not reached", diffId);
            return null;
        }

        String message = buildAlertMessage(diff, severityName);
        Instant now = Instant.now();

        int count = state.alertCount.incrementAndGet();
        state.lastAlertAt = now;

        AlertRecord record = new AlertRecord(
                diffId,
                severity,
                severityName,
                message,
                now,
                count,
                now,
                alertIntervalMs
        );

        synchronized (sentAlerts) {
            sentAlerts.add(record);
        }

        logger.info("Alert triggered for diff {}: severity={}, count={}, interval={}ms",
                diffId, severityName, count, alertIntervalMs);

        return record;
    }

    private String buildAlertMessage(InventoryDifference diff, String severityName) {
        return String.format("[%s] 资产ID: %s, 差异类型: %s, 系统数量: %d, 实际数量: %d, 差异值: %d",
                severityName,
                diff.getAssetId(),
                diff.getDiffType(),
                diff.getDiffSystem(),
                diff.getDiffActual(),
                diff.getDiffValue());
    }

    public void clearAlert(String diffId) {
        alertStates.remove(diffId);
        logger.info("Cleared alert state for diff: {}", diffId);
    }

    public void clearAllAlerts() {
        int count = alertStates.size();
        alertStates.clear();
        logger.info("Cleared all {} alert states", count);
    }

    public List<AlertRecord> getSentAlerts() {
        synchronized (sentAlerts) {
            return new ArrayList<>(sentAlerts);
        }
    }

    public List<AlertRecord> getSentAlertsBySeverity(SeverityLevel severity) {
        synchronized (sentAlerts) {
            List<AlertRecord> result = new ArrayList<>();
            for (AlertRecord alert : sentAlerts) {
                if (alert.getSeverity() == severity) {
                    result.add(alert);
                }
            }
            return result;
        }
    }

    public List<AlertRecord> getSentAlertsBySeverityKey(String severityKey) {
        SeverityLevel severity = SeverityLevel.fromString(severityKey);
        return getSentAlertsBySeverity(severity);
    }

    public int getSentAlertCount() {
        synchronized (sentAlerts) {
            return sentAlerts.size();
        }
    }

    public int getSentAlertCountBySeverity(SeverityLevel severity) {
        return getSentAlertsBySeverity(severity).size();
    }

    public int getActiveAlertCount() {
        return alertStates.size();
    }

    public long getAlertIntervalForSeverity(SeverityLevel severity) {
        return alertConfig.getAlertIntervalMs(severity.getConfigKey());
    }

    public double getThresholdForSeverity(SeverityLevel severity) {
        return alertConfig.getThreshold(severity.getConfigKey());
    }

    public String getNameForSeverity(SeverityLevel severity) {
        return alertConfig.getName(severity.getConfigKey());
    }

    public int getLevelForSeverity(SeverityLevel severity) {
        return alertConfig.getLevel(severity.getConfigKey());
    }

    public boolean isEnabled() {
        return alertConfig.isEnabled();
    }

    public List<String> getAvailableSeverities() {
        return alertConfig.getSeverity().keySet().stream().toList();
    }

    public void reset() {
        alertStates.clear();
        synchronized (sentAlerts) {
            sentAlerts.clear();
        }
        logger.info("Alert manager reset complete");
    }
}
