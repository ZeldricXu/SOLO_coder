package com.llmgateway.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("parse_task")
public class ParseTask implements Serializable {

    @TableId(value = "task_id", type = IdType.INPUT)
    private String taskId;

    @TableField("document_id")
    private String documentId;

    @TableField("pipeline_id")
    private String pipelineId;

    @TableField("status")
    private String status;

    @TableField("phase")
    private String phase;

    @TableField("progress")
    private Double progress;

    @TableField("chunk_count")
    private Integer chunkCount;

    @TableField("error_detail")
    private String errorDetail;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
