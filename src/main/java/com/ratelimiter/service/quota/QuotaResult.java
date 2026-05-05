package com.ratelimiter.service.quota;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaResult {
    
    private boolean allowed;
    private int remainingQuota;
    private int totalQuota;
    private String message;
    private int responseCode;
    
    public static QuotaResult allowed(int remainingQuota, int totalQuota) {
        return QuotaResult.builder()
                .allowed(true)
                .remainingQuota(remainingQuota)
                .totalQuota(totalQuota)
                .message("Quota available")
                .responseCode(200)
                .build();
    }
    
    public static QuotaResult rejected(String message, int responseCode) {
        return QuotaResult.builder()
                .allowed(false)
                .remainingQuota(0)
                .totalQuota(0)
                .message(message)
                .responseCode(responseCode)
                .build();
    }
}