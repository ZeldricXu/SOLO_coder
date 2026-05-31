package com.tsdbproxy.quality.dto;

import lombok.Data;

import java.util.Map;

@Data
public class QualityRuleCreateRequest {

    private String name;
    private String ruleType;
    private Long datasourceId;
    private String tableName;
    private String columnName;
    private Map<String, Object> ruleConfig;
    private String severity;
    private String cronExpression;
    private Integer enabled = 1;
}
