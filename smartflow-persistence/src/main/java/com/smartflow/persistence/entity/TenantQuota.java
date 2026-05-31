package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tenant_quota")
public class TenantQuota extends BaseEntity {

    private Long tenantId;
    private String resourceType;
    private Long quotaLimit;
    private Long usedAmount;
    private Long warningThreshold;
    private Integer status;
    private String remark;
}
