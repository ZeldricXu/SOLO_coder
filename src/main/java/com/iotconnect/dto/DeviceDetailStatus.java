package com.iotconnect.dto;

import java.time.LocalDateTime;
import java.util.Map;

public class DeviceDetailStatus {

    private String deviceId;
    private String deviceName;
    private String deviceType;
    private String deviceGroup;
    private String connectionStatus;
    private LocalDateTime lastActive;
    private LocalDateTime registeredAt;
    private LocalDateTime lastHeartbeat;
    private LocalDateTime connectionTime;
    private String clientAddress;
    private int activeAlerts;
    private Map<String, Integer> alertSeverityDistribution;

    public DeviceDetailStatus() {
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public String getDeviceGroup() {
        return deviceGroup;
    }

    public void setDeviceGroup(String deviceGroup) {
        this.deviceGroup = deviceGroup;
    }

    public String getConnectionStatus() {
        return connectionStatus;
    }

    public void setConnectionStatus(String connectionStatus) {
        this.connectionStatus = connectionStatus;
    }

    public LocalDateTime getLastActive() {
        return lastActive;
    }

    public void setLastActive(LocalDateTime lastActive) {
        this.lastActive = lastActive;
    }

    public LocalDateTime getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(LocalDateTime registeredAt) {
        this.registeredAt = registeredAt;
    }

    public LocalDateTime getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(LocalDateTime lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public LocalDateTime getConnectionTime() {
        return connectionTime;
    }

    public void setConnectionTime(LocalDateTime connectionTime) {
        this.connectionTime = connectionTime;
    }

    public String getClientAddress() {
        return clientAddress;
    }

    public void setClientAddress(String clientAddress) {
        this.clientAddress = clientAddress;
    }

    public int getActiveAlerts() {
        return activeAlerts;
    }

    public void setActiveAlerts(int activeAlerts) {
        this.activeAlerts = activeAlerts;
    }

    public Map<String, Integer> getAlertSeverityDistribution() {
        return alertSeverityDistribution;
    }

    public void setAlertSeverityDistribution(Map<String, Integer> alertSeverityDistribution) {
        this.alertSeverityDistribution = alertSeverityDistribution;
    }
}
