package com.chaoslab.modules.faultinject.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class FaultInjectRequest {

    @NotBlank(message = "场景ID不能为空")
    private String scenarioId;

    private List<String> targetOverrides;

    private BigDecimal errorRateThreshold;

    private BigDecimal latencyP99Threshold;

    private Boolean dryRun = false;
}
