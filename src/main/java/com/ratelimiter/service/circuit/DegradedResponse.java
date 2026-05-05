package com.ratelimiter.service.circuit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DegradedResponse {
    
    private int code;
    private String message;
    private long retryAfterMs;
}