package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("approval_flow")
public class ApprovalFlow extends TenantEntity {

    private String flowName;

    private String flowCode;

    private String flowType;

    private String description;

    private String flowDefinition;

    private Integer version;

    private Integer enabled;
}
