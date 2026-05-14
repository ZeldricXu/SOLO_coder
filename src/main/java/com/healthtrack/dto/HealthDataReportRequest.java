package com.healthtrack.dto;

public class HealthDataReportRequest {
    private String userId;
    private String dataType;
    private Double dataValue;
    private String dataUnit;
    private String deviceId;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public Double getDataValue() { return dataValue; }
    public void setDataValue(Double dataValue) { this.dataValue = dataValue; }
    public String getDataUnit() { return dataUnit; }
    public void setDataUnit(String dataUnit) { this.dataUnit = dataUnit; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
}
