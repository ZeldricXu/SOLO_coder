package com.datastandard.modules.slo.dto;

import jakarta.validation.constraints.NotBlank;
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
public class SliCalculationRequest {

    @NotBlank(message = "SLO ID不能为空")
    private String sloId;

    @NotNull(message = "开始时间不能为空")
    private Instant startTime;

    @NotNull(message = "结束时间不能为空")
    private Instant endTime;

    private String metricName;

    private Map<String, String> filters;

    private String aggregation;

    private List<DataPoint> dataPoints;

    private Long windowSizeSeconds;

    private Long slideIntervalSeconds;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DataPoint {
        private Instant timestamp;
        private Double value;
        private boolean success;
        private Map<String, String> labels;
    }
}
