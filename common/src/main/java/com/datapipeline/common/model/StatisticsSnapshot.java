package com.datapipeline.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatisticsSnapshot {

    private String snapshotId;
    private Instant timestamp;
    @Builder.Default
    private Map<String, Number> metrics = new HashMap<>();
    @Builder.Default
    private Map<String, String> dimensions = new HashMap<>();

    public StatisticsSnapshot metric(String key, Number value) {
        this.metrics.put(key, value);
        return this;
    }

    public StatisticsSnapshot dimension(String key, String value) {
        this.dimensions.put(key, value);
        return this;
    }

}
