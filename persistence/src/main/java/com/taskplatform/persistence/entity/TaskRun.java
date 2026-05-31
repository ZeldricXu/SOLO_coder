package com.taskplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskplatform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_runs")
public class TaskRun extends BaseEntity {

    @TableField("run_id")
    private String runId;

    @TableField("task_id")
    private String taskId;

    @TableField("phase")
    private String phase;

    @TableField("progress")
    private Double progress;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("status")
    private String status;

    @TableField("worker_id")
    private String workerId;

    @TableField("node_name")
    private String nodeName;

    @TableField("error_detail")
    private String errorDetail;

    @TableField("logs")
    private String logs;

    @TableField("metrics")
    private String metrics;
}
