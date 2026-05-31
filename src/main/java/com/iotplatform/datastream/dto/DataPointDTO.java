package com.iotplatform.datastream.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Map;

@Data
public class DataPointDTO {

    @NotBlank(message = "设备ID不能为空")
    private String deviceId;

    @NotBlank(message = "数据流ID不能为空")
    private String streamId;

    @NotBlank(message = "指标名称不能为空")
    private String metricName;

    @NotNull(message = "指标值不能为空")
    private BigDecimal metricValue;

    private Long timestamp;

    private Map<String, Object> tags;

    private Map<String, Object> attributes;
}
