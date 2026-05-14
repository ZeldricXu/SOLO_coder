package com.iotconnect.dto;

public class DataReportResponse {

    private String dataId;

    public DataReportResponse() {
    }

    public DataReportResponse(String dataId) {
        this.dataId = dataId;
    }

    public String getDataId() {
        return dataId;
    }

    public void setDataId(String dataId) {
        this.dataId = dataId;
    }
}
