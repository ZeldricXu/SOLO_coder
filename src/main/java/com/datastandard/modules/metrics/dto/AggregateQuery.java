package com.datastandard.modules.metrics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AggregateQuery {

    @NotBlank(message = "指标名称不能为空")
    private String metricName;

    @NotNull(message = "开始时间不能为空")
    private Instant startTime;

    @NotNull(message = "结束时间不能为空")
    private Instant endTime;

    private AggregateLevel aggregateLevel;

    private AggregateFunction aggregateFunction;

    private List<DimensionFilter> dimensionFilters;

    private List<String> groupByDimensions;

    private Downsampling downsampling;

    public enum AggregateLevel {
        RAW,
        MINUTE,
        HOUR,
        DAY
    }

    public enum AggregateFunction {
        SUM,
        AVG,
        MIN,
        MAX,
        COUNT,
        PERCENTILE_95,
        PERCENTILE_99
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Downsampling {
        private Integer intervalSeconds;
        private AggregateFunction function;
    }
}
