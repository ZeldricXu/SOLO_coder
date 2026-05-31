package com.dynamiclog.common.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class MetricsSnapshot extends BaseEntity {
    private String snapshotId;
    private LocalDateTime timestamp;
    private Map<String, Double> metrics;
    private Map<String, String> dimensions;
    private String namespace;
    private Long windowSizeMs;
}
