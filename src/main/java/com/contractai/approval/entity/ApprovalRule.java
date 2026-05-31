package com.contractai.approval.entity;

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
@TableName("approval_rule")
public class ApprovalRule extends TenantBaseEntity {

    private String ruleCode;

    private String ruleName;

    private String ruleType;

    private String businessType;

    private Integer priority;

    private String conditionExpression;

    private String approvalStrategy;

    private Integer approverCount;

    private BigDecimal approvalPercentage;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> approverConfig;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<Long> ccConfig;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> timeoutConfig;

    private Boolean enabled;

    private String description;
}
