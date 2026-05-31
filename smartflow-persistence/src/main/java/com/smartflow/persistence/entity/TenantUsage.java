package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tenant_usage")
public class TenantUsage extends BaseEntity {

    private Long tenantId;
    private String resourceType;
    private Long usageAmount;
    private Long quotaLimit;
    private BigDecimal unitPrice;
    private BigDecimal totalCost;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private String dimension;
}
