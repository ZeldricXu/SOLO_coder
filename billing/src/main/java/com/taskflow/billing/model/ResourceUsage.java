package com.taskflow.billing.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class ResourceUsage {
    private String usageId;
    private String tenantId;
    private String resourceType;
    private BigDecimal usageAmount;
    private String unit;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private Map<String, Object> dimensions;
    private LocalDateTime createdAt;
}
