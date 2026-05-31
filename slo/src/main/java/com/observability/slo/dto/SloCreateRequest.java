package com.observability.slo.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class SloCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private String sliMetric;
    private double target;
    private int timeWindow;
    private double burnRateThreshold;
    private Map<String, Object> notificationConfig;
}
