package com.loganalytics.test.builder;

import com.loganalytics.common.model.AggregatedMetric;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.common.util.IdUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class AggregatedMetricBuilder {
    private String id;
    private String metricName;
    private String serviceName;
    private LogLevel level;
    private String patternId;
    private String errorCode;
    private Instant windowStart;
    private Instant windowEnd;
    private Duration windowSize;
    private long count;
    private double value;
    private Map<String, String> tags;
    private Instant timestamp;

    public static AggregatedMetricBuilder anAggregatedMetric() {
        return new AggregatedMetricBuilder();
    }

    private AggregatedMetricBuilder() {
        this.id = IdUtils.generateId("metric");
        this.timestamp = Instant.now();
        this.tags = new HashMap<>();
    }

    public AggregatedMetricBuilder withId(String id) {
        this.id = id;
        return this;
    }

    public AggregatedMetricBuilder withMetricName(String metricName) {
        this.metricName = metricName;
        return this;
    }

    public AggregatedMetricBuilder withLogCountMetric() {
        return withMetricName("log_count");
    }

    public AggregatedMetricBuilder withErrorCountMetric() {
        return withMetricName("error_count");
    }

    public AggregatedMetricBuilder withErrorRateMetric() {
        return withMetricName("error_rate");
    }

    public AggregatedMetricBuilder withEpsMetric() {
        return withMetricName("eps");
    }

    public AggregatedMetricBuilder withServiceName(String serviceName) {
        this.serviceName = serviceName;
        return this;
    }

    public AggregatedMetricBuilder withPaymentService() {
        return withServiceName("payment-service");
    }

    public AggregatedMetricBuilder withUserService() {
        return withServiceName("user-service");
    }

    public AggregatedMetricBuilder withGatewayService() {
        return withServiceName("gateway-service");
    }

    public AggregatedMetricBuilder withLevel(LogLevel level) {
        this.level = level;
        return this;
    }

    public AggregatedMetricBuilder withPatternId(String patternId) {
        this.patternId = patternId;
        return this;
    }

    public AggregatedMetricBuilder withErrorCode(String errorCode) {
        this.errorCode = errorCode;
        return this;
    }

    public AggregatedMetricBuilder withWindow(Instant start, Duration size) {
        this.windowStart = start;
        this.windowSize = size;
        this.windowEnd = start.plus(size);
        return this;
    }

    public AggregatedMetricBuilder withOneMinuteWindow() {
        Instant now = Instant.now();
        Instant start = now.minusSeconds(now.getEpochSecond() % 60);
        return withWindow(start, Duration.ofMinutes(1));
    }

    public AggregatedMetricBuilder withFiveMinuteWindow() {
        Instant now = Instant.now();
        Instant start = now.minusSeconds(now.getEpochSecond() % 300);
        return withWindow(start, Duration.ofMinutes(5));
    }

    public AggregatedMetricBuilder withOneHourWindow() {
        Instant now = Instant.now();
        Instant start = now.minusSeconds(now.getEpochSecond() % 3600);
        return withWindow(start, Duration.ofHours(1));
    }

    public AggregatedMetricBuilder withCount(long count) {
        this.count = count;
        this.value = count;
        return this;
    }

    public AggregatedMetricBuilder withHighCount() {
        return withCount(10000);
    }

    public AggregatedMetricBuilder withMediumCount() {
        return withCount(1000);
    }

    public AggregatedMetricBuilder withLowCount() {
        return withCount(100);
    }

    public AggregatedMetricBuilder withValue(double value) {
        this.value = value;
        return this;
    }

    public AggregatedMetricBuilder withHighErrorRate() {
        return withErrorRateMetric().withValue(0.15);
    }

    public AggregatedMetricBuilder withLowErrorRate() {
        return withErrorRateMetric().withValue(0.01);
    }

    public AggregatedMetricBuilder withTag(String key, String value) {
        this.tags.put(key, value);
        return this;
    }

    public AggregatedMetricBuilder withTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public AggregatedMetricBuilder forServiceLevelCount(String service, LogLevel level, long count) {
        return withLogCountMetric()
                .withServiceName(service)
                .withLevel(level)
                .withOneMinuteWindow()
                .withCount(count)
                .withTag("level", level.name())
                .withTag("window", "1m");
    }

    public AggregatedMetric build() {
        AggregatedMetric metric = new AggregatedMetric();
        metric.setId(id);
        metric.setMetricName(metricName);
        metric.setServiceName(serviceName);
        metric.setLevel(level);
        metric.setPatternId(patternId);
        metric.setErrorCode(errorCode);
        metric.setWindowStart(windowStart);
        metric.setWindowEnd(windowEnd);
        metric.setWindowSize(windowSize);
        metric.setCount(count);
        metric.setValue(value);
        metric.setTags(tags);
        metric.setTimestamp(timestamp);
        return metric;
    }
}
