package com.ratelimiter.service.circuit;

import com.ratelimiter.model.CircuitBreakerState.CircuitState;
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
public class CircuitStateChangeEvent implements Serializable {
    
    private String circuitId;
    private String serviceName;
    private CircuitState fromState;
    private CircuitState toState;
    private int failureCount;
    private int successCount;
    private Instant timestamp;
    private String reason;
    
    public static CircuitStateChangeEvent create(String circuitId, String serviceName,
                                                   CircuitState fromState, CircuitState toState,
                                                   int failureCount, int successCount, String reason) {
        return CircuitStateChangeEvent.builder()
                .circuitId(circuitId)
                .serviceName(serviceName)
                .fromState(fromState)
                .toState(toState)
                .failureCount(failureCount)
                .successCount(successCount)
                .timestamp(Instant.now())
                .reason(reason)
                .build();
    }
}