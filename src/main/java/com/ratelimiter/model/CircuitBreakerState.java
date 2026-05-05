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
public class CircuitBreakerState implements Serializable {
    
    private String circuitId;
    private String serviceName;
    private CircuitState state;
    private int failureCount;
    private int successCount;
    private int failureThreshold;
    private int successThreshold;
    private long timeoutMs;
    private Instant lastStateChange;
    
    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
}