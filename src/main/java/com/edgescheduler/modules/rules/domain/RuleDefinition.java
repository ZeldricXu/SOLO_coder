package com.edgescheduler.modules.rules.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.edgescheduler.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rule_definition")
public class RuleDefinition extends BaseEntity {

    @TableField("rule_id")
    private String ruleId;

    @TableField("rule_name")
    private String ruleName;

    @TableField("rule_type")
    private String ruleType;

    @TableField("rule_description")
    private String ruleDescription;

    @TableField(value = "trigger_condition", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> triggerCondition;

    @TableField(value = "action_definition", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> actionDefinition;

    @TableField("execution_mode")
    private String executionMode;

    @TableField("priority")
    private Integer priority;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("trigger_count")
    private Long triggerCount;

    @TableField("last_triggered_at")
    private LocalDateTime lastTriggeredAt;

    @TableField("last_execution_result")
    private String lastExecutionResult;

    @TableField("last_error_message")
    private String lastErrorMessage;
}
