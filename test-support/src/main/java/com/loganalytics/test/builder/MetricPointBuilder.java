package com.loganalytics.test.builder;

import com.loganalytics.common.model.MetricPoint;
import com.loganalytics.common.util.IdUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class MetricPointBuilder {
    private String id;
    private String metricName;
    private Instant timestamp;
    private Instant windowStart;
    private Instant windowEnd;
    private double value;
    private MetricPoint.MetricType type;
    private Map<String, String> tags;

    public static MetricPointBuilder aMetricPoint() {
        return new MetricPointBuilder();
    }

    private MetricPointBuilder() {
        this.id = IdUtils.newMetricId();
        this.timestamp = Instant.now();
        this.type = MetricPoint.MetricType.GAUGE;
        this.tags = new HashMap<>();
    }

    public MetricPointBuilder withId(String id) {
        this.id = id;
        return this;
    }

    public MetricPointBuilder withMetricName(String metricName) {
        this.metricName = metricName;
        return this;
    }

    public MetricPointBuilder withLogCountMetric() {
        return withMetricName("log_count")
                .withType(MetricPoint.MetricType.COUNTER);
    }

    public MetricPointBuilder withErrorRateMetric() {
        return withMetricName("error_rate")
                .withType(MetricPoint.MetricType.GAUGE);
    }

    public MetricPointBuilder withEpsMetric() {
        return withMetricName("eps")
                .withType(MetricPoint.MetricType.GAUGE);
    }

    public MetricPointBuilder withErrorCountMetric() {
        return withMetricName("error_count")
                .withType(MetricPoint.MetricType.COUNTER);
    }

    public MetricPointBuilder withWarnCountMetric() {
        return withMetricName("warn_count")
                .withType(MetricPoint.MetricType.COUNTER);
    }

    public MetricPointBuilder withBytesProcessedMetric() {
        return withMetricName("bytes_processed")
                .withType(MetricPoint.MetricType.COUNTER);
    }

    public MetricPointBuilder withTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public MetricPointBuilder withWindow(Instant start, Instant end) {
        this.windowStart = start;
        this.windowEnd = end;
        this.timestamp = start;
        return this;
    }

    public MetricPointBuilder withOneMinuteWindow() {
        Instant now = Instant.now();
        return withWindow(now.minusSeconds(60), now)
                .withTag("window", "1min_tumbling");
    }

    public MetricPointBuilder withFiveMinuteWindow() {
        Instant now = Instant.now();
        return withWindow(now.minusSeconds(300), now)
                .withTag("window", "5min_hopping");
    }

    public MetricPointBuilder withValue(double value) {
        this.value = value;
        return this;
    }

    public MetricPointBuilder withType(MetricPoint.MetricType type) {
        this.type = type;
        return this;
    }

    public MetricPointBuilder withTag(String key, String value) {
        this.tags.put(key, value);
        return this;
    }

    public MetricPointBuilder withService(String serviceName) {
        return withTag("service", serviceName);
    }

    public MetricPointBuilder withPaymentService() {
        return withService("payment-service");
    }

    public MetricPointBuilder withGatewayService() {
        return withService("gateway-service");
    }

    public MetricPointBuilder withUserService() {
        return withService("user-service");
    }

    public MetricPointBuilder withLevel(String level) {
        return withTag("level", level);
    }

    public MetricPointBuilder withLevelError() {
        return withLevel("ERROR");
    }

    public MetricPointBuilder withLevelWarn() {
        return withLevel("WARN");
    }

    public MetricPointBuilder withLevelInfo() {
        return withLevel("INFO");
    }

    public MetricPointBuilder withErrorCode(String errorCode) {
        return withTag("error_code", errorCode);
    }

    public MetricPointBuilder withPatternId(String patternId) {
        return withTag("pattern_id", patternId);
    }

    public MetricPointBuilder withHighErrorRate() {
        return withErrorRateMetric()
                .withValue(0.15)
                .withLevelError();
    }

    public MetricPointBuilder withTags(Map<String, String> tags) {
        this.tags = new HashMap<>(tags);
        return this;
    }

    public MetricPoint build() {
        MetricPoint metric;
        if (windowStart != null && windowEnd != null) {
            metric = new MetricPoint(
                    id, metricName, windowStart, windowEnd, value, type
            );
        } else {
            metric = new MetricPoint(
                    id, metricName, timestamp, value, type
            );
        }
        metric.setTags(new HashMap<>(tags));
        return metric;
    }
}
