package com.contractai.sla.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sla_policy")
public class SlaPolicy extends TenantBaseEntity {

    private String policyCode;

    private String policyName;

    private String slaType;

    private Integer priority;

    private Integer responseTime;

    private Integer resolutionTime;

    private BigDecimal warningThreshold;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> escalationRules;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> notificationChannels;

    private Boolean enabled;

    private String description;
}
