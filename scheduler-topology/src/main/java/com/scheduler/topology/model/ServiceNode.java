package com.scheduler.topology.model;

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
public class ServiceNode {
    private String serviceName;
    private String host;
    private String version;
    private String status;
    private int instanceCount;
    private long requestCount;
    private long errorCount;
    private double avgLatencyMs;
    private double p99LatencyMs;
    private Instant lastSeen;
    private Map<String, String> metadata;
}
