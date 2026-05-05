package com.iotconnect.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

public class DataReportRequest {

    @NotBlank(message = "设备ID不能为空")
    @Size(max = 64, message = "设备ID长度不能超过64")
    private String deviceId;

    @NotBlank(message = "数据类型不能为空")
    @Size(max = 64, message = "数据类型长度不能超过64")
    private String dataType;

    @NotNull(message = "数据值不能为空")
    private Double value;

    @Size(max = 32, message = "单位长度不能超过32")
    private String unit;

    private Long timestamp;

    public DataReportRequest() {
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

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
}
