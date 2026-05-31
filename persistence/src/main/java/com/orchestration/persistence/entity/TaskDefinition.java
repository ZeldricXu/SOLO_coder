package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_definition")
public class TaskDefinition extends TenantEntity {

    private String taskCode;

    private String taskName;

    private String taskType;

    private String description;

    private String configJson;

    private String cronExpression;

    private Integer priority;

    private Integer timeoutSeconds;

    private Integer retryCount;

    private Integer status;
}
