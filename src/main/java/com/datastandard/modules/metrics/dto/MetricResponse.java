package com.datastandard.modules.metrics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricResponse {

    private String metricName;

    private Double value;

    private Map<String, String> dimensions;

    private Instant timestamp;

    private AggregateQuery.AggregateLevel aggregateLevel;

    private Map<String, Object> metadata;
}
