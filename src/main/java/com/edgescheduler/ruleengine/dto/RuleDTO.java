package com.edgescheduler.ruleengine.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class RuleDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String ruleId;

    @NotEmpty(message = "ruleName cannot be empty")
    private String ruleName;

    private String description;

    @NotEmpty(message = "triggerType cannot be empty")
    private String triggerType;

    private Map<String, Object> triggerConfig;

    private String conditionExpression;

    @NotEmpty(message = "actionType cannot be empty")
    private String actionType;

    private Map<String, Object> actionConfig;

    private Integer enabled;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
