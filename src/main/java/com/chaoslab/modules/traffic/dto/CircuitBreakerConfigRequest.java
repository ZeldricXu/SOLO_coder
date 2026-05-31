package com.chaoslab.modules.traffic.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CircuitBreakerConfigRequest {

    @NotBlank(message = "策略ID不能为空")
    private String strategyId;

    private Integer failureThreshold = 5;

    private Integer failureThresholdPercentage = 50;

    private Integer waitDurationInOpenState = 30000;

    private Integer permittedNumberOfCallsInHalfOpenState = 3;

    private Integer slidingWindowSize = 100;

    private String slidingWindowType = "COUNT_BASED";

    private Integer slowCallDurationThreshold = 5000;

    private Integer slowCallRateThreshold = 100;
}
