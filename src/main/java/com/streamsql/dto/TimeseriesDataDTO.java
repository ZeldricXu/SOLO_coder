package com.streamsql.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class TimeseriesDataDTO {

    @NotBlank(message = "指标名称不能为空")
    private String metricName;

    @NotNull(message = "时间戳不能为空")
    private LocalDateTime timestamp;

    @NotNull(message = "指标值不能为空")
    private Double metricValue;

    private Map<String, Object> tags;
}
