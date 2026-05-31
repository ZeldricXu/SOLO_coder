package com.solocoder.domain.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatsSnapshot {
    private String snapshotId;
    private Instant timestamp;
    private Map<String, Double> metrics;
    private Map<String, String> dimensions;
}
