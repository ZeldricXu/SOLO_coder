package com.loganalytics.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class MetricPoint {
    public enum MetricType {
        COUNTER, GAUGE, HISTOGRAM, SUMMARY
    }

    private String id;
    private String metricName;
    private Instant timestamp;
    private Instant windowStart;
    private Instant windowEnd;
    private double value;
    private MetricType type;
    private Map<String, String> tags;

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    public MetricPoint() {
        this.tags = new HashMap<>();
    }

    public MetricPoint(String id, String metricName, Instant timestamp, double value, MetricType type) {
        this();
        this.id = id;
        this.metricName = metricName;
        this.timestamp = timestamp;
        this.value = value;
        this.type = type;
    }

    public MetricPoint(String id, String metricName, Instant windowStart, Instant windowEnd, double value, MetricType type) {
        this(id, metricName, windowStart, value, type);
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    public void addTag(String key, String value) {
        if (value != null) {
            this.tags.put(key, value);
        }
    }

    public String getTag(String key) {
        return this.tags.get(key);
    }

    @JsonIgnore
    public String getTagsAsJson() {
        try {
            return objectMapper.writeValueAsString(this.tags);
        } catch (Exception e) {
            return "{}";
        }
    }

    public static MetricPoint fromJson(String json) {
        try {
            return objectMapper.readValue(json, MetricPoint.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse MetricPoint JSON", e);
        }
    }

    public String toJson() {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize MetricPoint to JSON", e);
        }
    }

    public static Map<String, String> tagsFromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }

    public String getName() { return metricName; }
    public void setName(String name) { this.metricName = name; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }

    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }

    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }

    public MetricType getType() { return type; }
    public void setType(MetricType type) { this.type = type; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
