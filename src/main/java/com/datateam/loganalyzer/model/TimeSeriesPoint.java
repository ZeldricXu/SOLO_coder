package com.datateam.loganalyzer.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class TimeSeriesPoint {
    private Instant windowStart;
    private Instant windowEnd;
    private long durationSeconds;
    private long totalCount;
    private Map<LogLevel, Long> levelCounts;
    private Map<String, Long> serviceCounts;
    private Map<String, Long> serviceErrorCounts;
    private Map<String, Long> errorTypeCounts;
    private double ratePerSecond;
    private double ratePerMinute;

    public TimeSeriesPoint() {
        this.levelCounts = new LinkedHashMap<>();
        this.serviceCounts = new LinkedHashMap<>();
        this.serviceErrorCounts = new LinkedHashMap<>();
        this.errorTypeCounts = new LinkedHashMap<>();
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(Instant windowStart) {
        this.windowStart = windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(Instant windowEnd) {
        this.windowEnd = windowEnd;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(long totalCount) {
        this.totalCount = totalCount;
    }

    public void incrementTotal() {
        this.totalCount++;
    }

    public Map<LogLevel, Long> getLevelCounts() {
        return levelCounts;
    }

    public void setLevelCounts(Map<LogLevel, Long> levelCounts) {
        this.levelCounts = levelCounts;
    }

    public void incrementLevel(LogLevel level) {
        this.levelCounts.merge(level, 1L, Long::sum);
    }

    public long getLevelCount(LogLevel level) {
        return this.levelCounts.getOrDefault(level, 0L);
    }

    public Map<String, Long> getServiceCounts() {
        return serviceCounts;
    }

    public void setServiceCounts(Map<String, Long> serviceCounts) {
        this.serviceCounts = serviceCounts;
    }

    public void incrementService(String service) {
        if (service != null) {
            this.serviceCounts.merge(service, 1L, Long::sum);
        }
    }

    public void incrementServiceError(String service) {
        if (service != null) {
            this.serviceErrorCounts.merge(service, 1L, Long::sum);
        }
    }

    public Map<String, Long> getServiceErrorCounts() {
        return serviceErrorCounts;
    }

    public void setServiceErrorCounts(Map<String, Long> serviceErrorCounts) {
        this.serviceErrorCounts = serviceErrorCounts;
    }

    public Map<String, Long> getErrorTypeCounts() {
        return errorTypeCounts;
    }

    public void setErrorTypeCounts(Map<String, Long> errorTypeCounts) {
        this.errorTypeCounts = errorTypeCounts;
    }

    public void incrementErrorType(String errorType) {
        if (errorType != null) {
            this.errorTypeCounts.merge(errorType, 1L, Long::sum);
        }
    }

    public double getRatePerSecond() {
        return ratePerSecond;
    }

    public void setRatePerSecond(double ratePerSecond) {
        this.ratePerSecond = ratePerSecond;
    }

    public double getRatePerMinute() {
        return ratePerMinute;
    }

    public void setRatePerMinute(double ratePerMinute) {
        this.ratePerMinute = ratePerMinute;
    }

    public void calculateRates() {
        if (durationSeconds > 0) {
            this.ratePerSecond = (double) totalCount / durationSeconds;
            this.ratePerMinute = this.ratePerSecond * 60.0;
        }
    }

    public long getErrorCount() {
        return getLevelCount(LogLevel.ERROR) + getLevelCount(LogLevel.FATAL);
    }

    public long getWarnCount() {
        return getLevelCount(LogLevel.WARN);
    }

    @Override
    public String toString() {
        return String.format("Window[%s -> %s] total=%d, errors=%d, rate=%.2f/min",
            windowStart, windowEnd, totalCount, getErrorCount(), ratePerMinute);
    }
}
