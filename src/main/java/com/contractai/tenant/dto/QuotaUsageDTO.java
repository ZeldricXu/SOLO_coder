package com.contractai.tenant.dto;

import lombok.Data;

@Data
public class QuotaUsageDTO {

    private String resourceType;
    private Long usageAmount;
    private String source;
    private String sourceId;
}
