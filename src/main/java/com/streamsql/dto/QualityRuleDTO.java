package com.streamsql.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class QualityRuleDTO {

    @NotBlank(message = "规则名称不能为空")
    private String ruleName;

    @NotBlank(message = "规则类型不能为空")
    private String ruleType;

    @NotBlank(message = "数据源ID不能为空")
    private String datasourceId;

    @NotBlank(message = "表名不能为空")
    private String tableName;

    private String columnName;

    @NotBlank(message = "校验表达式不能为空")
    private String checkExpression;

    private String severity = "warning";

    private Boolean enabled = true;

    private String cronExpression;
}
