package com.observability.metrics.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class MetricAggregation implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private long count;
    private double sum;
    private double avg;
    private double min;
    private double max;
    private double p50;
    private double p95;
    private double p99;
    private Map<String, String> labels;
}
