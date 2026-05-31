package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tenant_config")
public class TenantConfig extends BaseEntity {

    private Long tenantId;
    private String configKey;
    private String configValue;
    private String configType;
    private String description;
    private Integer enabled;
}
