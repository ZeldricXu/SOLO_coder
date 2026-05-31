package com.logmanager.domain.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class MetricsSnapshot extends BaseEntity {
    private String snapshotId;
    private Instant timestamp;
    private Map<String, Double> metrics = new HashMap<>();
    private Map<String, String> dimensions = new HashMap<>();
}
