package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("quality_rule")
public class QualityRule extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String ruleId;

    private String ruleName;

    private String ruleType;

    private String datasourceId;

    private String tableName;

    private String columnName;

    private String checkExpression;

    private String severity;

    private Boolean enabled;

    private String cronExpression;

    private LocalDateTime lastCheckTime;
}
