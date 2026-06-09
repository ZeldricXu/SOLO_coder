package com.loganalytics.common.model;

import java.time.Instant;
import java.util.Map;

public class AnomalyEvent {
    public enum AnomalyType {
        FREQUENCY_ANOMALY,
        CONTENT_ANOMALY,
        CORRELATION_ANOMALY,
        THRESHOLD_BREACH
    }

    public enum Severity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    private String id;
    private AnomalyType type;
    private Severity severity;
    private Instant detectedAt;
    private Instant windowStart;
    private Instant windowEnd;
    private String serviceName;
    private String patternId;
    private String patternTemplate;
    private String description;
    private double expectedValue;
    private double actualValue;
    private double deviation;
    private double sigmaScore;
    private Map<String, Object> details;
    private boolean acknowledged;
    private Instant acknowledgedAt;
    private String acknowledgedBy;

    public AnomalyEvent() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public AnomalyType getType() { return type; }
    public void setType(AnomalyType type) { this.type = type; }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }

    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }

    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getPatternId() { return patternId; }
    public void setPatternId(String patternId) { this.patternId = patternId; }

    public String getPatternTemplate() { return patternTemplate; }
    public void setPatternTemplate(String patternTemplate) { this.patternTemplate = patternTemplate; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public double getExpectedValue() { return expectedValue; }
    public void setExpectedValue(double expectedValue) { this.expectedValue = expectedValue; }

    public double getActualValue() { return actualValue; }
    public void setActualValue(double actualValue) { this.actualValue = actualValue; }

    public double getDeviation() { return deviation; }
    public void setDeviation(double deviation) { this.deviation = deviation; }

    public double getSigmaScore() { return sigmaScore; }
    public void setSigmaScore(double sigmaScore) { this.sigmaScore = sigmaScore; }

    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }

    public boolean isAcknowledged() { return acknowledged; }
    public void setAcknowledged(boolean acknowledged) { this.acknowledged = acknowledged; }

    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }

    public String getAcknowledgedBy() { return acknowledgedBy; }
    public void setAcknowledgedBy(String acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }
}
