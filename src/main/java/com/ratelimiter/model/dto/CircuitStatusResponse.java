package com.ratelimiter.model.dto;

import com.ratelimiter.model.CircuitBreakerState.CircuitState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CircuitStatusResponse {
    
    private CircuitState state;
    private int failureCount;
    private int successCount;
}