package com.ratelimiter.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitCheckResponse {
    
    private boolean allowed;
    private int remainingQuota;
    private String message;
}