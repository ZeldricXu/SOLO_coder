package com.ratelimiter.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitCheckRequest {
    
    private String requestId;
    private String target;
    private String clientId;
}