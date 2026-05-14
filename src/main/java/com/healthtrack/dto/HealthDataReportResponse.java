package com.healthtrack.dto;

public class HealthDataReportResponse {
    private String dataId;
    private String indicatorStatus;

    public HealthDataReportResponse() {}

    public HealthDataReportResponse(String dataId, String indicatorStatus) {
        this.dataId = dataId;
        this.indicatorStatus = indicatorStatus;
    }

    public String getDataId() { return dataId; }
    public void setDataId(String dataId) { this.dataId = dataId; }
    public String getIndicatorStatus() { return indicatorStatus; }
    public void setIndicatorStatus(String indicatorStatus) { this.indicatorStatus = indicatorStatus; }
}
