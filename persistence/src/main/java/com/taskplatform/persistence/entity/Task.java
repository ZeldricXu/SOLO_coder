package com.taskplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskplatform.common.entity.BaseEntity;
import com.taskplatform.common.enums.TaskPriority;
import com.taskplatform.common.enums.TaskStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tasks")
public class Task extends BaseEntity {

    @TableField("task_id")
    private String taskId;

    @TableField("type")
    private String type;

    @TableField("status")
    private TaskStatus status;

    @TableField("priority")
    private TaskPriority priority;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("config")
    private String config;

    @TableField("payload")
    private String payload;

    @TableField("namespace")
    private String namespace;

    @TableField("queue_name")
    private String queueName;

    @TableField("max_retries")
    private Integer maxRetries = 3;

    @TableField("retry_count")
    private Integer retryCount = 0;

    @TableField("timeout_seconds")
    private Integer timeoutSeconds;

    @TableField("scheduled_at")
    private LocalDateTime scheduledAt;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("parent_task_id")
    private String parentTaskId;

    @TableField("error_message")
    private String errorMessage;

    @TableField("result_data")
    private String resultData;

    @TableField("labels")
    private String labels;

    @TableField("created_by")
    private String createdBy;
}
