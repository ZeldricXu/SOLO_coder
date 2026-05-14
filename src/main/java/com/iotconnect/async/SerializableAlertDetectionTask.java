package com.iotconnect.async;

import java.io.Serializable;
import java.time.Instant;

public class SerializableAlertDetectionTask implements Serializable {

    private static final long serialVersionUID = 1L;

    private String deviceId;
    private String deviceName;
    private String deviceType;
    private String deviceGroup;
    private String protocol;
    private String connectionStatus;
    private String authToken;

    private String dataId;
    private String dataType;
    private Double value;
    private String unit;
    private Instant collectedAt;
    private String quality;

    private Instant queuedAt;

    public SerializableAlertDetectionTask() {
        this.queuedAt = Instant.now();
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

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getConnectionStatus() {
        return connectionStatus;
    }

    public void setConnectionStatus(String connectionStatus) {
        this.connectionStatus = connectionStatus;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public String getDataId() {
        return dataId;
    }

    public void setDataId(String dataId) {
        this.dataId = dataId;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public Double getValue() {
        return value;
    }

    public void setValue(Double value) {
        this.value = value;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public void setCollectedAt(Instant collectedAt) {
        this.collectedAt = collectedAt;
    }

    public String getQuality() {
        return quality;
    }

    public void setQuality(String quality) {
        this.quality = quality;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public void setQueuedAt(Instant queuedAt) {
        this.queuedAt = queuedAt;
    }

    public static SerializableAlertDetectionTask fromDeviceAndData(
            com.iotconnect.entity.Device device,
            com.iotconnect.entity.DeviceData deviceData) {
        SerializableAlertDetectionTask task = new SerializableAlertDetectionTask();
        
        task.setDeviceId(device.getDeviceId());
        task.setDeviceName(device.getDeviceName());
        task.setDeviceType(device.getDeviceType());
        task.setDeviceGroup(device.getDeviceGroup());
        task.setProtocol(device.getProtocol());
        task.setConnectionStatus(device.getConnectionStatus());
        task.setAuthToken(device.getAuthToken());
        
        task.setDataId(deviceData.getDataId());
        task.setDataType(deviceData.getDataType());
        task.setValue(deviceData.getValue());
        task.setUnit(deviceData.getUnit());
        task.setCollectedAt(deviceData.getCollectedAt());
        task.setQuality(deviceData.getQuality());
        
        return task;
    }

    public com.iotconnect.entity.Device toDevice() {
        com.iotconnect.entity.Device device = new com.iotconnect.entity.Device();
        
        device.setDeviceId(this.deviceId);
        device.setDeviceName(this.deviceName);
        device.setDeviceType(this.deviceType);
        device.setDeviceGroup(this.deviceGroup);
        device.setProtocol(this.protocol);
        device.setConnectionStatus(this.connectionStatus);
        device.setAuthToken(this.authToken);
        
        return device;
    }

    public com.iotconnect.entity.DeviceData toDeviceData() {
        com.iotconnect.entity.DeviceData deviceData = new com.iotconnect.entity.DeviceData();
        
        deviceData.setDataId(this.dataId);
        deviceData.setDeviceId(this.deviceId);
        deviceData.setDataType(this.dataType);
        deviceData.setValue(this.value);
        deviceData.setUnit(this.unit);
        deviceData.setCollectedAt(this.collectedAt);
        deviceData.setQuality(this.quality);
        
        return deviceData;
    }
}
