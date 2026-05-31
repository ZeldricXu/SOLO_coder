package com.contractai.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("assignment_strategy")
public class AssignmentStrategy extends TenantBaseEntity {

    private String strategyCode;

    private String strategyName;

    private String strategyType;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<String> ticketTypes;

    private BigDecimal skillMatchWeight;

    private BigDecimal loadBalanceWeight;

    private BigDecimal efficiencyWeight;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> config;

    private Boolean enabled;

    private String description;
}
