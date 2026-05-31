package com.cdcsync.quality.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cdcsync.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cdc_data_quality_rule")
public class QualityRule extends BaseEntity {

    private String name;

    private String ruleType;

    private String dataSourceId;

    private String tableName;

    private String columnName;

    private String ruleExpression;

    private String expectedValue;

    private String severity;

    private Integer enabled;

    private String scheduleCron;

    private LocalDateTime lastCheckAt;

    private String lastCheckResult;
}
