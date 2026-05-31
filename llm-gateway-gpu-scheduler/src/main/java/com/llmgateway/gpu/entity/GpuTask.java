package com.llmgateway.gpu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("gpu_task")
public class GpuTask implements Serializable {

    @TableId(value = "task_id", type = IdType.INPUT)
    private String taskId;

    @TableField("task_name")
    private String taskName;

    @TableField("task_type")
    private String taskType;

    @TableField("priority")
    private Integer priority;

    @TableField("required_gpu_count")
    private Integer requiredGpuCount;

    @TableField("required_memory_gb")
    private Integer requiredMemoryGb;

    @TableField("node_id")
    private String nodeId;

    @TableField(value = "gpu_indices", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<Integer> gpuIndices;

    @TableField("status")
    private String status;

    @TableField("command")
    private String command;

    @TableField("output_path")
    private String outputPath;

    @TableField("progress")
    private Double progress;

    @TableField("pid")
    private Integer pid;

    @TableField("error_detail")
    private String errorDetail;

    @TableField("submitter")
    private String submitter;

    @TableField("queued_at")
    private LocalDateTime queuedAt;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
