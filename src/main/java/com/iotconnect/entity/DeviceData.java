package com.iotconnect.entity;

import java.time.Instant;

public class DeviceData {

    private String dataId;
    private String deviceId;
    private String dataType;
    private Double value;
    private String unit;
    private Instant collectedAt;
    private String quality;

    public DeviceData() {
    }

    public String getDataId() {
        return dataId;
    }

    public void setDataId(String dataId) {
        this.dataId = dataId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
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
}
