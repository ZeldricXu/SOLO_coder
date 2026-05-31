package com.monitoring.alert.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRule {

    private String ruleId;

    private String name;

    private String description;

    private String namespace;

    private String metricName;

    private String operator;

    private Double threshold;

    private Integer durationSeconds;

    private String severity;

    private List<String> notificationChannels;

    private Map<String, String> labels;

    private Map<String, String> annotations;

    private Boolean enabled;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant updatedAt;
}
