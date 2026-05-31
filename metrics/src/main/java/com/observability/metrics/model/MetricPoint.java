package com.observability.metrics.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class MetricPoint implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private double value;
    private LocalDateTime timestamp;
    private Map<String, String> labels;
    private String unit;
    private String type;
}
