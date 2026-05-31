package com.logmanager.domain.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class TimeSeriesMetric extends BaseEntity {
    private String metricId;
    private String metricName;
    private Double value;
    private Instant timestamp;
    private Map<String, String> labels = new HashMap<>();
}
