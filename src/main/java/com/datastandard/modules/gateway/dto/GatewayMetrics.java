package com.datastandard.modules.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayMetrics {

    private String instanceId;

    private Instant timestamp;

    private long totalRequests;

    private long successRequests;

    private long failedRequests;

    private long blockedRequests;

    private long rateLimitedRequests;

    private long circuitOpenRequests;

    private double averageResponseTimeMs;

    private double p95ResponseTimeMs;

    private double p99ResponseTimeMs;

    private Map<String, AtomicLong> requestsByPath;

    private Map<String, AtomicLong> requestsByMethod;

    private Map<String, AtomicLong> requestsByStatus;

    private int activeConnections;

    private int availablePermits;
}
