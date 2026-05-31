package com.llmgateway.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("run_instance")
public class RunInstance {

    @TableId(value = "run_id", type = IdType.ASSIGN_ID)
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

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
