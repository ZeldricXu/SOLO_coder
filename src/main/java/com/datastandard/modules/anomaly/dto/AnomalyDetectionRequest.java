package com.datastandard.modules.anomaly.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyDetectionRequest {

    @NotBlank(message = "检测编码不能为空")
    private String detectionCode;

    @NotBlank(message = "指标编码不能为空")
    private String metricCode;

    private Long entityId;

    private Long instanceId;

    @NotEmpty(message = "检测数据不能为空")
    @Valid
    private List<DataPoint> dataPoints;

    @Valid
    private AlgorithmConfig algorithmConfig;

    private List<String> dimensions;

    private Map<String, String> tags;

    private String severityLevel;

    private LocalDateTime windowStart;

    private LocalDateTime windowEnd;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DataPoint {
        @NotNull(message = "时间戳不能为空")
        private LocalDateTime timestamp;

        @NotNull(message = "数据值不能为空")
        private BigDecimal value;

        private Map<String, String> dimensionValues;
    }
}
