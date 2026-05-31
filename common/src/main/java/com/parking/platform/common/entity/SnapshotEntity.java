package com.parking.platform.common.entity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class SnapshotEntity extends BaseEntity {

    private Instant timestamp;
    private Map<String, Double> metrics;
    private Map<String, String> dimensions;

    public SnapshotEntity() {
        super();
        this.timestamp = Instant.now();
        this.metrics = new HashMap<>();
        this.dimensions = new HashMap<>();
    }

    @Override
    protected String getIdPrefix() {
        return "snap";
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Double> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, Double> metrics) {
        this.metrics = metrics;
    }

    public Map<String, String> getDimensions() {
        return dimensions;
    }

    public void setDimensions(Map<String, String> dimensions) {
        this.dimensions = dimensions;
    }

    public void addMetric(String key, Double value) {
        this.metrics.put(key, value);
    }

    public void addDimension(String key, String value) {
        this.dimensions.put(key, value);
    }

    public Double getMetric(String key) {
        return metrics.get(key);
    }

    public String getDimension(String key) {
        return dimensions.get(key);
    }

    public double getThroughput() {
        Double value = getMetric("throughput");
        return value != null ? value : 0.0;
    }

    public double getLatencyP99() {
        Double value = getMetric("latency_p99");
        return value != null ? value : 0.0;
    }

    public double getErrorRate() {
        Double value = getMetric("error_rate");
        return value != null ? value : 0.0;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("snapshot_id", getId());
        map.put("timestamp", timestamp);
        map.put("metrics", metrics);
        map.put("dimensions", dimensions);
        return map;
    }
}
