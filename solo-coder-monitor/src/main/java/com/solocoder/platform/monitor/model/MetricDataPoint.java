package com.solocoder.platform.monitor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricDataPoint implements Serializable {

    private static final long serialVersionUID = 1L;

    private String metricName;
    private double value;
    private Map<String, String> tags;
    private LocalDateTime timestamp;
    private String unit;
}
