package com.chaoslab.modules.sidecar.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class ResourceLimitUpdateRequest {

    private String instanceId;
    private BigDecimal cpuLimit;
    private BigDecimal memoryLimit;
    private BigDecimal cpuRequest;
    private BigDecimal memoryRequest;
}
