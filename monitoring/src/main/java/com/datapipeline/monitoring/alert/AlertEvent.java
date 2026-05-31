package com.datapipeline.monitoring.alert;

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
public class AlertEvent {

    public enum Status {
        FIRING,
        RESOLVED
    }

    private String alertId;
    private String ruleId;
    private String metricName;
    private Number value;
    private Number threshold;
    private AlertRule.Severity severity;
    private Status status;
    @Builder.Default
    private Map<String, String> labels = java.util.Collections.emptyMap();
    private String message;
    @Builder.Default
    private Instant timestamp = Instant.now();
    private Instant startedAt;
    private Instant resolvedAt;
    private long durationMs;

}
