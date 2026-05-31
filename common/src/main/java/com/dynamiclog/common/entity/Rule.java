package com.dynamiclog.common.entity;

import com.dynamiclog.common.enums.RuleConditionType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class Rule extends BaseEntity {
    private String name;
    private String description;
    private String eventType;
    private RuleConditionType conditionType;
    private String conditionExpression;
    private String actionType;
    private String actionConfig;
    private Boolean enabled;
    private Integer priority = 0;
    private List<String> tags = new ArrayList<>();
    private String namespace;
}
