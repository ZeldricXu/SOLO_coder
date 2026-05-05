package com.ratelimiter.service.limiter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitResult {
    
    private boolean allowed;
    private int remainingQuota;
    private String message;
    private int responseCode;
    
    public static RateLimitResult allowed(int remainingQuota) {
        return RateLimitResult.builder()
                .allowed(true)
                .remainingQuota(remainingQuota)
                .message("Request allowed")
                .responseCode(200)
                .build();
    }
    
    public static RateLimitResult rejected(String message, int responseCode) {
        return RateLimitResult.builder()
                .allowed(false)
                .remainingQuota(0)
                .message(message)
                .responseCode(responseCode)
                .build();
    }
}