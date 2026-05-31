package com.contractai.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public abstract class TenantBaseEntity extends BaseEntity {

    @TableField(value = "tenant_id")
    private Long tenantId;
}
