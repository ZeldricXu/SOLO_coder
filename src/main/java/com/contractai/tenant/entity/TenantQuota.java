package com.contractai.tenant.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tenant_quota")
public class TenantQuota extends TenantBaseEntity {

    @TableField("resource_type")
    private String resourceType;

    @TableField("quota_limit")
    private Long quotaLimit;

    @TableField("quota_used")
    private Long quotaUsed;

    @TableField("unit")
    private String unit;

    @TableField("reset_period")
    private String resetPeriod;

    @TableField("last_reset_at")
    private LocalDateTime lastResetAt;

    @TableField("warning_threshold")
    private BigDecimal warningThreshold;

    @TableField(value = "attributes", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> attributes;
}
