package com.loganalytics.common.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public class AggregatedMetric {
    private String id;
    private String metricName;
    private Instant windowStart;
    private Instant windowEnd;
    private String windowType;
    private String serviceName;
    private LogLevel level;
    private String patternId;
    private String errorCode;
    private long count;
    private double eps;
    private double errorRate;
    private Duration windowSize;
    private double value;
    private Map<String, String> tags;
    private Instant timestamp;

    public AggregatedMetric() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }

    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }

    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }

    public String getWindowType() { return windowType; }
    public void setWindowType(String windowType) { this.windowType = windowType; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public LogLevel getLevel() { return level; }
    public void setLevel(LogLevel level) { this.level = level; }

    public String getPatternId() { return patternId; }
    public void setPatternId(String patternId) { this.patternId = patternId; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }

    public double getEps() { return eps; }
    public void setEps(double eps) { this.eps = eps; }

    public double getErrorRate() { return errorRate; }
    public void setErrorRate(double errorRate) { this.errorRate = errorRate; }

    public Duration getWindowSize() { return windowSize; }
    public void setWindowSize(Duration windowSize) { this.windowSize = windowSize; }

    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
