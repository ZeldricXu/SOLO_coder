package com.tracetopology.domain.entity;

import com.tracetopology.common.utils.IdGenerator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Snapshot {

    private String snapshotId;
    private Instant timestamp;
    private Map<String, Double> metrics;
    private Map<String, String> dimensions;

    public static Snapshot create(Map<String, Double> metrics, Map<String, String> dimensions) {
        return Snapshot.builder()
                .snapshotId(IdGenerator.generateId("snap"))
                .timestamp(Instant.now())
                .metrics(metrics)
                .dimensions(dimensions)
                .build();
    }

    public Double getMetric(String name) {
        return metrics.get(name);
    }

    public String getDimension(String name) {
        return dimensions.get(name);
    }
}
