package com.datastandard.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.Map;

@Getter
public class AnomalyDetectedEvent extends ApplicationEvent {

    private final String detectionId;
    private final String metricName;
    private final String severity;
    private final Double anomalyScore;
    private final Instant detectedAt;
    private final Map<String, Object> dimensions;
    private final Map<String, Object> details;
    private final String traceId;

    public AnomalyDetectedEvent(Object source, String detectionId, String metricName,
                                String severity, Double anomalyScore,
                                Map<String, Object> dimensions, Map<String, Object> details,
                                String traceId) {
        super(source);
        this.detectionId = detectionId;
        this.metricName = metricName;
        this.severity = severity;
        this.anomalyScore = anomalyScore;
        this.detectedAt = Instant.now();
        this.dimensions = dimensions;
        this.details = details;
        this.traceId = traceId;
    }
}
