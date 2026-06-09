package com.loganalytics.common.model;

import java.time.Instant;

public class AggregatedMetric {
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

    public AggregatedMetric() {}

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
}
