package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "gpu_task", autoResultMap = true)
public class GpuTask extends BaseEntity {

    @TableField("task_id")
    private String taskId;

    @TableField("name")
    private String name;

    @TableField("task_type")
    private String taskType;

    @TableField("priority")
    private Integer priority;

    @TableField("required_gpu_memory_gb")
    private BigDecimal requiredGpuMemoryGb;

    @TableField("gpu_count")
    private Integer gpuCount;

    @TableField("node_id")
    private String nodeId;

    @TableField("gpu_indices")
    private String gpuIndices;

    @TableField("status")
    private String status;

    @TableField("preemptible")
    private Boolean preemptible;

    @TableField("preempted_by")
    private String preemptedBy;

    @TableField("command")
    private String command;

    @TableField(value = "parameters", typeHandler = JacksonTypeHandler.class)
    private ObjectNode parameters;

    @TableField("error_detail")
    private String errorDetail;

    @TableField("submitted_by")
    private String submittedBy;

    @TableField("submitted_at")
    private LocalDateTime submittedAt;

    @TableField("scheduled_at")
    private LocalDateTime scheduledAt;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
