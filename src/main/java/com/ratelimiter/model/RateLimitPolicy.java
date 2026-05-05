package com.ratelimiter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitPolicy implements Serializable {
    
    private String policyId;
    private String targetType;
    private String targetValue;
    private String algorithm;
    private int threshold;
    private int windowSize;
    private int burstSize;
    private String actionOnLimit;
    private int responseCode;
    private String responseMessage;
    
    public enum Algorithm {
        FIXED_WINDOW,
        SLIDING_WINDOW,
        TOKEN_BUCKET
    }
    
    public enum TargetType {
        API_PATH,
        CLIENT_ID,
        USER_ID,
        IP_ADDRESS
    }
    
    public enum ActionOnLimit {
        REJECT,
        DELAY,
        DEGRADE
    }
}