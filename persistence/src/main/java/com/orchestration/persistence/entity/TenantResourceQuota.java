package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tenant_resource_quota")
public class TenantResourceQuota extends TenantEntity {

    private String resourceType;

    private Long quotaLimit;

    private Long quotaUsed;

    private String unit;

    private BigDecimal warningThreshold;
}
