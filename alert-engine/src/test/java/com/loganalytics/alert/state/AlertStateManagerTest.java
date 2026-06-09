package com.loganalytics.alert.state;

import com.loganalytics.alert.config.AlertEngineConfig;
import com.loganalytics.common.model.Alert;
import com.loganalytics.common.model.AlertRule;
import com.loganalytics.common.model.AnomalyEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.common.util.IdUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AlertStateManagerTest {

    private AlertStateManager stateManager;
    private AlertEngineConfig config;

    @BeforeEach
    void setUp() {
        config = new AlertEngineConfig();
        config.setDefaultCooldownPeriod(Duration.ofMinutes(5));
        config.setDefaultEscalationDelay(Duration.ofMinutes(5));
        stateManager = new AlertStateManager(config);
    }

    @Test
    void testCreateNewAlert() {
        AlertRule rule = createTestRule();
        rule.setMinFiringDurationMinutes(0);

        Optional<Alert> alertOpt = stateManager.checkAndCreateAlert(
                rule, "payment-service",
                Map.of("errorRate", 0.08),
                "High error rate",
                "Error rate exceeded threshold"
        );

        assertTrue(alertOpt.isPresent());
        Alert alert = alertOpt.get();
        assertNotNull(alert.getId());
        assertEquals(rule.getId(), alert.getRuleId());
        assertEquals(Alert.AlertStatus.FIRING, alert.getStatus());
        assertEquals("payment-service", alert.getServiceName());
        assertEquals(1, alert.getEscalationLevel());
    }

    @Test
    void testMinFiringDuration() {
        AlertRule rule = createTestRule();
        rule.setMinFiringDurationMinutes(5);

        Optional<Alert> alertOpt = stateManager.checkAndCreateAlert(
                rule, "payment-service",
                Map.of("errorRate", 0.08),
                "High error rate",
                "Error rate exceeded threshold"
        );

        assertFalse(alertOpt.isPresent());

        List<Alert> activeAlerts = stateManager.getActiveAlerts();
        assertTrue(activeAlerts.isEmpty());
    }

    @Test
    void testAlertAcknowledgement() {
        AlertRule rule = createTestRule();
        rule.setMinFiringDurationMinutes(0);

        Optional<Alert> alertOpt = stateManager.checkAndCreateAlert(
                rule, "payment-service",
                Map.of("errorRate", 0.08),
                "High error rate",
                "Error rate exceeded threshold"
        );

        assertTrue(alertOpt.isPresent());
        Alert alert = alertOpt.get();

        stateManager.acknowledgeAlert(alert.getId(), "engineer@example.com");

        assertEquals(Alert.AlertStatus.ACKNOWLEDGED, alert.getStatus());
        assertEquals("engineer@example.com", alert.getAcknowledgedBy());
        assertNotNull(alert.getAcknowledgedAt());
    }

    @Test
    void testAlertResolution() {
        AlertRule rule = createTestRule();
        rule.setMinFiringDurationMinutes(0);

        Optional<Alert> alertOpt = stateManager.checkAndCreateAlert(
                rule, "payment-service",
                Map.of("errorRate", 0.08),
                "High error rate",
                "Error rate exceeded threshold"
        );

        assertTrue(alertOpt.isPresent());
        Alert alert = alertOpt.get();

        stateManager.resolveAlert(alert.getId());

        assertEquals(Alert.AlertStatus.RESOLVED, alert.getStatus());
        assertNotNull(alert.getResolvedAt());
        assertTrue(stateManager.getActiveAlerts().isEmpty());
    }

    @Test
    void testCooldownPeriod() {
        AlertRule rule = createTestRule();
        rule.setMinFiringDurationMinutes(0);

        Optional<Alert> alertOpt = stateManager.checkAndCreateAlert(
                rule, "payment-service",
                Map.of("errorRate", 0.08),
                "High error rate",
                "Error rate exceeded threshold"
        );

        assertTrue(alertOpt.isPresent());
        Alert alert = alertOpt.get();

        stateManager.resolveAlert(alert.getId());

        Optional<Alert> secondAlertOpt = stateManager.checkAndCreateAlert(
                rule, "payment-service",
                Map.of("errorRate", 0.08),
                "High error rate",
                "Error rate exceeded threshold"
        );

        assertFalse(secondAlertOpt.isPresent());
    }

    @Test
    void testNotificationThrottling() {
        AlertRule rule = createTestRule();
        rule.setMinFiringDurationMinutes(0);

        Optional<Alert> alertOpt = stateManager.checkAndCreateAlert(
                rule, "payment-service",
                Map.of("errorRate", 0.08),
                "High error rate",
                "Error rate exceeded threshold"
        );

        assertTrue(alertOpt.isPresent());
        Alert alert = alertOpt.get();

        assertTrue(stateManager.shouldSendNotification(alert));
        stateManager.markNotificationSent(alert);

        assertFalse(stateManager.shouldSendNotification(alert));
        assertEquals(1, alert.getNotificationCount());
    }

    @Test
    void testExistingAlertUpdate() {
        AlertRule rule = createTestRule();
        rule.setMinFiringDurationMinutes(0);

        Optional<Alert> alertOpt1 = stateManager.checkAndCreateAlert(
                rule, "payment-service",
                Map.of("errorRate", 0.08),
                "High error rate",
                "Error rate exceeded threshold"
        );

        assertTrue(alertOpt1.isPresent());
        Alert alert1 = alertOpt1.get();
        String firstUpdatedAt = alert1.getUpdatedAt().toString();

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Optional<Alert> alertOpt2 = stateManager.checkAndCreateAlert(
                rule, "payment-service",
                Map.of("errorRate", 0.10),
                "High error rate",
                "Error rate exceeded threshold"
        );

        assertTrue(alertOpt2.isPresent());
        Alert alert2 = alertOpt2.get();

        assertEquals(alert1.getId(), alert2.getId());
        assertNotEquals(firstUpdatedAt, alert2.getUpdatedAt().toString());
    }

    private AlertRule createTestRule() {
        AlertRule rule = new AlertRule();
        rule.setId(IdUtils.generateId("rule"));
        rule.setName("Test Rule");
        rule.setEnabled(true);
        rule.setConditionType(AlertRule.ConditionType.ERROR_RATE);
        rule.setOperator(AlertRule.Operator.GT);
        rule.setThreshold(0.05);
        rule.setSeverity(AnomalyEvent.Severity.HIGH);
        rule.setServiceFilter(List.of("*"));
        rule.setLevelFilter(List.of(LogLevel.ERROR));
        rule.setNotificationChannels(List.of(AlertRule.NotificationChannel.EMAIL));
        rule.setNotificationTargets(List.of("test@example.com"));
        rule.setCooldownPeriod(Duration.ofMinutes(5));
        rule.setEscalationDelay(Duration.ofMinutes(5));
        rule.setMaxEscalationLevel(3);
        rule.setCreatedBy("test");
        rule.setCreatedAt(System.currentTimeMillis());
        rule.setUpdatedAt(System.currentTimeMillis());
        return rule;
    }
}
