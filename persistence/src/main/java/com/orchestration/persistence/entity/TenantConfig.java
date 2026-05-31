package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tenant_config")
public class TenantConfig extends TenantEntity {

    private String configKey;

    private String configValue;

    private String configType;

    private String description;
}
