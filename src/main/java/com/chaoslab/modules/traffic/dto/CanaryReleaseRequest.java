package com.chaoslab.modules.traffic.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CanaryReleaseRequest {

    @NotBlank(message = "策略ID不能为空")
    private String strategyId;

    private Integer targetPercentage;

    private BigDecimal stepSize;

    private Integer stepIntervalMinutes;

    private Boolean autoRollback = true;

    private BigDecimal errorRateThreshold;
}
