package com.taskflow.data.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskflow.common.model.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task")
public class TaskEntity extends TenantEntity {

    @TableField("task_id")
    private String taskId;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("type")
    private String type;

    @TableField("status")
    private String status;

    @TableField("cron_expression")
    private String cronExpression;

    @TableField("next_run_time")
    private LocalDateTime nextRunTime;

    @TableField("last_run_time")
    private LocalDateTime lastRunTime;

    @TableField("parameters")
    private String parameters;

    @TableField("handler_class")
    private String handlerClass;

    @TableField("timeout_seconds")
    private Integer timeoutSeconds;

    @TableField("max_retry")
    private Integer maxRetry;

    @TableField("flow_id")
    private String flowId;

    @TableField(exist = false)
    private Map<String, Object> parametersMap;
}
