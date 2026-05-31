package com.tsdbproxy.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_quality_rule")
public class QualityRule extends BaseEntity {

    private String name;

    private String ruleType;

    private Long datasourceId;

    private String tableName;

    private String columnName;

    private String ruleConfig;

    private String severity;

    private String cronExpression;

    private Integer enabled;

    private LocalDateTime lastCheckTime;
}
