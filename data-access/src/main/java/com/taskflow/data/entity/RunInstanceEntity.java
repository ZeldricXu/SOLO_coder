package com.taskflow.data.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskflow.common.model.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("run_instance")
public class RunInstanceEntity extends TenantEntity {

    @TableField("run_id")
    private String runId;

    @TableField("entity_id")
    private String entityId;

    @TableField("phase")
    private String phase;

    @TableField("progress")
    private Double progress;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("error_detail")
    private String errorDetail;

    @TableField("config_id")
    private String configId;

    @TableField("trigger_type")
    private String triggerType;

    @TableField("executor")
    private String executor;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("parent_run_id")
    private String parentRunId;
}
