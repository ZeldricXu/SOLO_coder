package com.datastandard.modules.metrics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricIngestRequest {

    @NotBlank(message = "指标名称不能为空")
    private String metricName;

    @NotNull(message = "指标值不能为空")
    private Double value;

    private Map<String, String> dimensions;

    private Instant timestamp;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchRequest {
        @NotEmpty(message = "批量指标数据不能为空")
        private List<MetricIngestRequest> metrics;
    }
}
