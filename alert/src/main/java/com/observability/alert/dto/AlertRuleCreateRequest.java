package com.observability.alert.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class AlertRuleCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private String metricName;
    private String expression;
    private String level;
    private Double threshold;
    private Integer duration;
    private Map<String, Object> notificationConfig;
}
