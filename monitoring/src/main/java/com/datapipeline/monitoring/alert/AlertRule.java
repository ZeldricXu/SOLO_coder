package com.datapipeline.monitoring.alert;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRule {

    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    public enum Operator {
        GT,
        LT,
        GTE,
        LTE,
        EQ,
        NEQ
    }

    private String ruleId;
    private String metricName;
    private Operator operator;
    private Number threshold;
    private Severity severity;
    private Duration evaluationWindow;
    @Builder.Default
    private boolean enabled = true;
    @Builder.Default
    private List<String> notificationChannels = new ArrayList<>();
    @Builder.Default
    private Map<String, String> labels = java.util.Collections.emptyMap();

}
