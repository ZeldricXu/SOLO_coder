package com.iotconnect.service;

import com.iotconnect.async.AlertDetectionProcessor;
import com.iotconnect.entity.AlertEvent;
import com.iotconnect.entity.AlertRule;
import com.iotconnect.entity.Device;
import com.iotconnect.entity.DeviceData;
import com.iotconnect.enums.AlertOperator;
import com.iotconnect.enums.AlertSeverity;
import com.iotconnect.enums.AlertStatus;
import com.iotconnect.repository.AlertEventRepository;
import com.iotconnect.repository.AlertRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AlertEngineService implements AlertDetectionProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AlertEngineService.class);

    private final AlertRuleRepository alertRuleRepository;
    private final AlertEventRepository alertEventRepository;
    private final NotificationService notificationService;

    private final ConcurrentHashMap<String, LocalDateTime> lastAlertTimeMap = new ConcurrentHashMap<>();

    @Value("${alert.aggregation.window-minutes:5}")
    private int aggregationWindowMinutes;

    @Value("${alert.async-detection.enabled:true}")
    private boolean asyncDetectionEnabled;

    public AlertEngineService(AlertRuleRepository alertRuleRepository,
                               AlertEventRepository alertEventRepository,
                               NotificationService notificationService) {
        this.alertRuleRepository = alertRuleRepository;
        this.alertEventRepository = alertEventRepository;
        this.notificationService = notificationService;
    }

    @Override
    @Transactional
    public void processAlertDetection(Device device, DeviceData deviceData) {
        doProcessAlertDetection(device, deviceData);
    }

    @Async("notificationExecutor")
    public void processAlertDetectionAsync(Device device, DeviceData deviceData) {
        logger.debug("Starting async alert detection: deviceId={}, metric={}",
                device.getDeviceId(), deviceData.getDataType());
        
        try {
            doProcessAlertDetection(device, deviceData);
        } catch (Exception e) {
            logger.error("Async alert detection failed: deviceId={}, metric={}, error={}",
                    device.getDeviceId(), deviceData.getDataType(), e.getMessage(), e);
        }
    }

    @Transactional
    public void processAlertDetection(Device device, DeviceData deviceData) {
        doProcessAlertDetection(device, deviceData);
    }

    private void doProcessAlertDetection(Device device, DeviceData deviceData) {
        String deviceType = device.getDeviceType();
        String metric = deviceData.getDataType();
        Double value = deviceData.getValue();

        List<AlertRule> rules = alertRuleRepository.findByDeviceTypeAndMetricAndEnabledTrue(deviceType, metric);

        if (rules.isEmpty()) {
            logger.debug("No alert rules found for deviceType={}, metric={}", deviceType, metric);
            checkForResolvedAlerts(device.getDeviceId(), metric);
            return;
        }

        for (AlertRule rule : rules) {
            processRule(device, deviceData, rule);
        }
    }

    private void processRule(Device device, DeviceData deviceData, AlertRule rule) {
        AlertOperator operator = AlertOperator.fromValue(rule.getOperator());
        boolean thresholdTriggered = operator.evaluate(deviceData.getValue(), rule.getThreshold());

        String deviceId = device.getDeviceId();
        String ruleId = rule.getRuleId();

        if (thresholdTriggered) {
            handleThresholdTriggered(deviceId, rule, deviceData);
        } else {
            handleThresholdNotTriggered(deviceId, ruleId);
        }
    }

    private void handleThresholdTriggered(String deviceId, AlertRule rule, DeviceData deviceData) {
        String ruleId = rule.getRuleId();
        String silenceKey = deviceId + "_" + ruleId;

        LocalDateTime lastAlertTime = lastAlertTimeMap.get(silenceKey);
        LocalDateTime now = LocalDateTime.now();

        List<AlertEvent> existingActiveAlerts = alertEventRepository.findActiveAlertsByDeviceIdAndRuleId(deviceId, ruleId);

        if (!existingActiveAlerts.isEmpty()) {
            AlertEvent existingAlert = existingActiveAlerts.get(0);
            
            if (lastAlertTime != null && 
                isWithinAggregationWindow(lastAlertTime, now)) {
                existingAlert.setAggregationCount(existingAlert.getAggregationCount() + 1);
                existingAlert.setMetricValue(deviceData.getValue());
                alertEventRepository.save(existingAlert);
                logger.debug("Alert aggregated: deviceId={}, ruleId={}, count={}", 
                        deviceId, ruleId, existingAlert.getAggregationCount());
                return;
            }
            
            lastAlertTimeMap.put(silenceKey, now);
            return;
        }

        if (lastAlertTime != null && 
            isWithinSilencePeriod(lastAlertTime, now, rule.getSilenceDurationSeconds())) {
            logger.debug("Alert in silence period: deviceId={}, ruleId={}", deviceId, ruleId);
            return;
        }

        AlertEvent alertEvent = createAlertEvent(deviceId, rule, deviceData);
        AlertEvent savedEvent = alertEventRepository.save(alertEvent);

        try {
            notificationService.sendAlertNotification(savedEvent);
            savedEvent.setNotificationSent(true);
            savedEvent.setNotificationSentAt(LocalDateTime.now());
            alertEventRepository.save(savedEvent);
        } catch (Exception e) {
            logger.error("Failed to send notification for alert: alertId={}, error={}", 
                    savedEvent.getAlertId(), e.getMessage());
        }

        lastAlertTimeMap.put(silenceKey, now);
        logger.info("Alert triggered: deviceId={}, ruleId={}, alertId={}, value={}, threshold={}",
                deviceId, ruleId, savedEvent.getAlertId(), deviceData.getValue(), rule.getThreshold());
    }

    private void handleThresholdNotTriggered(String deviceId, String ruleId) {
        List<AlertEvent> activeAlerts = alertEventRepository.findActiveAlertsByDeviceIdAndRuleId(deviceId, ruleId);

        for (AlertEvent alert : activeAlerts) {
            if (AlertStatus.TRIGGERED.getValue().equals(alert.getStatus())) {
                alert.setStatus(AlertStatus.RESOLVED.getValue());
                alert.setResolvedAt(LocalDateTime.now());
                alertEventRepository.save(alert);

                try {
                    notificationService.sendRecoveryNotification(alert);
                } catch (Exception e) {
                    logger.error("Failed to send recovery notification: alertId={}, error={}", 
                            alert.getAlertId(), e.getMessage());
                }

                String silenceKey = deviceId + "_" + ruleId;
                lastAlertTimeMap.remove(silenceKey);

                logger.info("Alert resolved: deviceId={}, ruleId={}, alertId={}",
                        deviceId, ruleId, alert.getAlertId());
            }
        }
    }

    private void checkForResolvedAlerts(String deviceId, String metric) {
        List<AlertEvent> activeAlerts = alertEventRepository.findActiveAlertsByDeviceId(deviceId);
        
        for (AlertEvent alert : activeAlerts) {
            Optional<AlertRule> ruleOpt = alertRuleRepository.findById(alert.getRuleId());
            if (ruleOpt.isPresent()) {
                AlertRule rule = ruleOpt.get();
                if (rule.getMetric().equals(metric)) {
                    handleThresholdNotTriggered(deviceId, alert.getRuleId());
                }
            }
        }
    }

    private AlertEvent createAlertEvent(String deviceId, AlertRule rule, DeviceData deviceData) {
        AlertEvent event = new AlertEvent();
        event.setAlertId(generateAlertId());
        event.setDeviceId(deviceId);
        event.setRuleId(rule.getRuleId());
        event.setMetricValue(deviceData.getValue());
        event.setThreshold(rule.getThreshold());
        event.setSeverity(rule.getSeverity());
        event.setStatus(AlertStatus.TRIGGERED.getValue());
        event.setTriggeredAt(LocalDateTime.now());
        event.setNotificationSent(false);
        event.setAggregationCount(1);
        
        AlertSeverity severity = AlertSeverity.fromValue(rule.getSeverity());
        String description = String.format("设备[%s]指标[%s]值为%.2f，触发告警规则[%s]，阈值为%.2f",
                deviceId, rule.getMetric(), deviceData.getValue(), rule.getRuleName(), rule.getThreshold());
        event.setDescription(description);

        return event;
    }

    private boolean isWithinAggregationWindow(LocalDateTime lastTime, LocalDateTime now) {
        return lastTime.plusMinutes(aggregationWindowMinutes).isAfter(now);
    }

    private boolean isWithinSilencePeriod(LocalDateTime lastTime, LocalDateTime now, Integer silenceSeconds) {
        if (silenceSeconds == null || silenceSeconds <= 0) {
            silenceSeconds = 300;
        }
        return lastTime.plusSeconds(silenceSeconds).isAfter(now);
    }

    public List<AlertEvent> getActiveAlerts() {
        return alertEventRepository.findByStatus(AlertStatus.TRIGGERED.getValue());
    }

    public List<AlertEvent> getAlertsByDevice(String deviceId) {
        return alertEventRepository.findAlertHistoryByDeviceId(deviceId);
    }

    public Optional<AlertEvent> getAlert(String alertId) {
        return alertEventRepository.findById(alertId);
    }

    @Transactional
    public AlertEvent acknowledgeAlert(String alertId) {
        Optional<AlertEvent> alertOpt = alertEventRepository.findById(alertId);
        
        if (alertOpt.isEmpty()) {
            throw new RuntimeException("Alert not found: " + alertId);
        }

        AlertEvent alert = alertOpt.get();
        alert.setStatus(AlertStatus.ACKNOWLEDGED.getValue());
        
        AlertEvent savedAlert = alertEventRepository.save(alert);
        logger.info("Alert acknowledged: alertId={}", alertId);
        
        return savedAlert;
    }

    public boolean isAsyncDetectionEnabled() {
        return asyncDetectionEnabled;
    }

    private String generateAlertId() {
        return "alert_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
