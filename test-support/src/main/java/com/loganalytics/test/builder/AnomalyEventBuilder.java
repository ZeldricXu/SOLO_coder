package com.loganalytics.test.builder;

import com.loganalytics.common.model.AnomalyEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.common.util.IdUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class AnomalyEventBuilder {
    private String id;
    private AnomalyEvent.AnomalyType type;
    private AnomalyEvent.Severity severity;
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
    private String level;
    private String traceId;

    public static AnomalyEventBuilder anAnomalyEvent() {
        return new AnomalyEventBuilder();
    }

    private AnomalyEventBuilder() {
        this.id = IdUtils.generateId("anomaly");
        this.detectedAt = Instant.now();
        this.details = new HashMap<>();
    }

    public AnomalyEventBuilder withId(String id) {
        this.id = id;
        return this;
    }

    public AnomalyEventBuilder withType(AnomalyEvent.AnomalyType type) {
        this.type = type;
        return this;
    }

    public AnomalyEventBuilder withFrequencyAnomalyType() {
        return withType(AnomalyEvent.AnomalyType.FREQUENCY_ANOMALY)
                .withSeverityHigh();
    }

    public AnomalyEventBuilder withContentAnomalyType() {
        return withType(AnomalyEvent.AnomalyType.CONTENT_ANOMALY)
                .withSeverityMedium();
    }

    public AnomalyEventBuilder withCorrelationAnomalyType() {
        return withType(AnomalyEvent.AnomalyType.CORRELATION_ANOMALY)
                .withSeverityHigh();
    }

    public AnomalyEventBuilder withThresholdBreachType() {
        return withType(AnomalyEvent.AnomalyType.THRESHOLD_BREACH)
                .withSeverityCritical();
    }

    public AnomalyEventBuilder withSeverity(AnomalyEvent.Severity severity) {
        this.severity = severity;
        return this;
    }

    public AnomalyEventBuilder withSeverityLow() {
        return withSeverity(AnomalyEvent.Severity.LOW);
    }

    public AnomalyEventBuilder withSeverityMedium() {
        return withSeverity(AnomalyEvent.Severity.MEDIUM);
    }

    public AnomalyEventBuilder withSeverityHigh() {
        return withSeverity(AnomalyEvent.Severity.HIGH);
    }

    public AnomalyEventBuilder withSeverityCritical() {
        return withSeverity(AnomalyEvent.Severity.CRITICAL);
    }

    public AnomalyEventBuilder withDetectedAt(Instant detectedAt) {
        this.detectedAt = detectedAt;
        return this;
    }

    public AnomalyEventBuilder withWindow(Instant start, Instant end) {
        this.windowStart = start;
        this.windowEnd = end;
        return this;
    }

    public AnomalyEventBuilder withLastMinutesWindow(int minutes) {
        Instant end = Instant.now();
        Instant start = end.minusSeconds(minutes * 60L);
        return withWindow(start, end);
    }

    public AnomalyEventBuilder withServiceName(String serviceName) {
        this.serviceName = serviceName;
        return this;
    }

    public AnomalyEventBuilder withPaymentService() {
        return withServiceName("payment-service");
    }

    public AnomalyEventBuilder withGatewayService() {
        return withServiceName("gateway-service");
    }

    public AnomalyEventBuilder withPatternId(String patternId) {
        this.patternId = patternId;
        return this;
    }

    public AnomalyEventBuilder withPatternTemplate(String patternTemplate) {
        this.patternTemplate = patternTemplate;
        return this;
    }

    public AnomalyEventBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public AnomalyEventBuilder withSigmaScoreAnomaly(double sigmaThreshold) {
        this.sigmaScore = sigmaThreshold + 0.5;
        this.deviation = sigmaScore;
        return this;
    }

    public AnomalyEventBuilder withThreeSigmaAnomaly() {
        return withSigmaScoreAnomaly(3.0)
                .withDescription("Frequency exceeded 3σ threshold");
    }

    public AnomalyEventBuilder withFiveSigmaAnomaly() {
        return withSigmaScoreAnomaly(5.0)
                .withDescription("Frequency exceeded 5σ threshold - critical spike");
    }

    public AnomalyEventBuilder withExpectedValue(double expected) {
        this.expectedValue = expected;
        return this;
    }

    public AnomalyEventBuilder withActualValue(double actual) {
        this.actualValue = actual;
        return this;
    }

    public AnomalyEventBuilder withDeviation(double deviation) {
        this.deviation = deviation;
        return this;
    }

    public AnomalyEventBuilder withSigmaScore(double sigma) {
        this.sigmaScore = sigma;
        return this;
    }

    public AnomalyEventBuilder withDetail(String key, Object value) {
        this.details.put(key, value);
        return this;
    }

    public AnomalyEventBuilder withLevel(String level) {
        this.level = level;
        return this;
    }

    public AnomalyEventBuilder withLevel(LogLevel level) {
        this.level = level != null ? level.name() : null;
        return this;
    }

    public AnomalyEventBuilder withLevelError() {
        return withLevel(LogLevel.ERROR);
    }

    public AnomalyEventBuilder withLevelWarn() {
        return withLevel(LogLevel.WARN);
    }

    public AnomalyEventBuilder withTraceId(String traceId) {
        this.traceId = traceId;
        return this;
    }

    public AnomalyEventBuilder withPatternIds(java.util.List<String> patternIds) {
        return withDetail("affectedPatterns", patternIds);
    }

    public AnomalyEventBuilder withMissingCounterpart(String errorPattern, String warnPattern) {
        return withDetail("errorPattern", errorPattern)
                .withDetail("expectedWarnPattern", warnPattern)
                .withDescription(String.format("No corresponding WARN pattern '%s' found after ERROR '%s'",
                        warnPattern, errorPattern));
    }

    public AnomalyEvent build() {
        AnomalyEvent event = new AnomalyEvent();
        event.setId(id);
        event.setType(type);
        event.setSeverity(severity);
        event.setDetectedAt(detectedAt);
        event.setWindowStart(windowStart);
        event.setWindowEnd(windowEnd);
        event.setServiceName(serviceName);
        event.setPatternId(patternId);
        event.setPatternTemplate(patternTemplate);
        event.setDescription(description);
        event.setExpectedValue(expectedValue);
        event.setActualValue(actualValue);
        event.setDeviation(deviation);
        event.setSigmaScore(sigmaScore);
        event.setDetails(details);
        event.setLevel(level);
        event.setTraceId(traceId);
        return event;
    }
}
