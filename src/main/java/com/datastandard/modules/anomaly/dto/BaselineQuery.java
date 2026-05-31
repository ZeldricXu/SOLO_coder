package com.datastandard.modules.anomaly.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BaselineQuery {

    private String metricCode;
    private Long entityId;
    private Long instanceId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private List<String> dimensions;
    private Map<String, String> dimensionFilters;
    private String aggregation;
    private Integer periodDays;
    private Boolean includeSeasonal;
    private String seasonalityType;
}
