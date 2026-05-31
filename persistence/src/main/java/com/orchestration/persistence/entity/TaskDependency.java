package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_dependency")
public class TaskDependency extends TenantEntity {

    private Long taskId;

    private Long dependentTaskId;

    private String dependencyType;
}
