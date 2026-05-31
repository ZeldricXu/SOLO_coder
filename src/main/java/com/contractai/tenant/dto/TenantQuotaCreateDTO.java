package com.contractai.tenant.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Map;

@Data
public class TenantQuotaCreateDTO {

    private String resourceType;
    private Long quotaLimit;
    private String unit;
    private String resetPeriod;
    private BigDecimal warningThreshold;
    private Map<String, Object> attributes;
}
