package com.deviceops.service.alert;

import com.deviceops.entity.AlertRecord;
import com.deviceops.entity.FaultRecord;
import com.deviceops.exception.DeviceOpsException;
import com.deviceops.repository.AlertRecordRepository;
import com.deviceops.service.config.DynamicConfigService;
import com.deviceops.service.device.DeviceService;
import com.deviceops.service.history.HistoryService;
import com.deviceops.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AlertService {

    @Autowired
    private AlertRecordRepository alertRecordRepository;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private DynamicConfigService dynamicConfigService;

    @Transactional
    public AlertRecord sendAlert(String deviceId, String alertType, String alertLevel) {
        if (!deviceService.exists(deviceId)) {
            throw DeviceOpsException.deviceNotFound(deviceId);
        }

        int maxRetries = dynamicConfigService.getAlertMaxRetries(alertLevel);

        AlertRecord alert = new AlertRecord();
        alert.setAlertId(IdGenerator.generateAlertId());
        alert.setDeviceId(deviceId);
        alert.setAlertType(alertType);
        alert.setAlertLevel(alertLevel);
        alert.setAlertStatus("sent");
        alert.setAlertTime(LocalDateTime.now());
        alert.setAcknowledged(false);
        alert.setMaxRetries(maxRetries);
        alert.setRetryCount(0);

        AlertRecord saved = alertRecordRepository.save(alert);

        historyService.recordAlert(deviceId, alertType, alertLevel);

        sendNotification(saved);

        return saved;
    }

    @Transactional
    public AlertRecord sendAlertForFault(String deviceId, FaultRecord fault) {
        String alertLevel = fault.getFaultLevel();
        String alertType = "fault_" + fault.getFaultType();
        return sendAlert(deviceId, alertType, alertLevel);
    }

    @Transactional
    public AlertRecord sendStatusAbnormalAlert(String deviceId) {
        return sendAlert(deviceId, "status_abnormal", "high");
    }

    @Transactional
    public AlertRecord sendWarningAlert(String deviceId) {
        return sendAlert(deviceId, "status_warning", "medium");
    }

    @Transactional
    public AlertRecord acknowledgeAlert(String alertId) {
        AlertRecord alert = getAlert(alertId);
        alert.setAcknowledged(true);
        return alertRecordRepository.save(alert);
    }

    public AlertRecord getAlert(String alertId) {
        return alertRecordRepository.findById(alertId)
                .orElseThrow(() -> new DeviceOpsException(404, "预警记录不存在: " + alertId));
    }

    public List<AlertRecord> getAlertsByDevice(String deviceId) {
        if (!deviceService.exists(deviceId)) {
            throw DeviceOpsException.deviceNotFound(deviceId);
        }
        return alertRecordRepository.findByDeviceIdOrderByAlertTimeDesc(deviceId);
    }

    public List<AlertRecord> getUnacknowledgedAlerts() {
        return alertRecordRepository.findByAcknowledged(false);
    }

    public List<AlertRecord> getAlertsByLevel(String level) {
        return alertRecordRepository.findByAlertLevel(level);
    }

    public List<AlertRecord> getAllAlerts() {
        return alertRecordRepository.findAll();
    }

    private void sendNotification(AlertRecord alert) {
        System.out.println("[预警通知] 设备: " + alert.getDeviceId() + 
                ", 类型: " + alert.getAlertType() + 
                ", 级别: " + alert.getAlertLevel() +
                ", 时间: " + alert.getAlertTime());
    }

    public void checkAndSendAlert(String deviceId, String status) {
        if ("abnormal".equals(status)) {
            sendStatusAbnormalAlert(deviceId);
        } else if ("warning".equals(status)) {
            sendWarningAlert(deviceId);
        }
    }

    @Transactional
    public AlertRecord retryAlert(String alertId) {
        AlertRecord alert = getAlert(alertId);
        
        if (alert.getAcknowledged()) {
            throw new RuntimeException("预警已确认，无需重试");
        }
        
        if (alert.getRetryCount() >= alert.getMaxRetries()) {
            alert.setAlertStatus("failed");
            return alertRecordRepository.save(alert);
        }
        
        alert.setRetryCount(alert.getRetryCount() + 1);
        alert.setAlertTime(LocalDateTime.now());
        alert.setAlertStatus("retried");
        
        AlertRecord saved = alertRecordRepository.save(alert);
        
        sendNotification(saved);
        
        return saved;
    }

    public int getMaxRetriesForLevel(String alertLevel) {
        return dynamicConfigService.getAlertMaxRetries(alertLevel);
    }

    public boolean canRetry(String alertId) {
        AlertRecord alert = getAlert(alertId);
        return !alert.getAcknowledged() && alert.getRetryCount() < alert.getMaxRetries();
    }

    @Transactional
    public AlertRecord acknowledgeAndClear(String alertId) {
        AlertRecord alert = acknowledgeAlert(alertId);
        alert.setAlertStatus("acknowledged");
        return alertRecordRepository.save(alert);
    }
}
