package com.ratelimiter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficStatistics implements Serializable {
    
    private String statId;
    private String target;
    private long totalRequests;
    private long passedRequests;
    private long rejectedRequests;
    private long avgResponseTime;
    private double errorRate;
    private String statPeriod;
    private Instant collectedAt;
}