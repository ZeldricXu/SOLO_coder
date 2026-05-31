package com.solocoder.dns.traffic.model;

import lombok.Data;
import java.io.Serializable;

@Data
public class CircuitBreakerConfig implements Serializable {
    private String serviceName;
    private Integer failureThreshold;
    private Integer timeoutMs;
    private Integer retryAttempts;
    private Integer waitDurationInOpenStateMs;
    private Integer slowCallRateThreshold;
    private Integer slowCallDurationThresholdMs;
}
