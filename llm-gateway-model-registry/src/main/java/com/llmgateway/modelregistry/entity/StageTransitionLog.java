package com.llmgateway.modelregistry.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("stage_transition_log")
public class StageTransitionLog implements Serializable {

    @TableId(value = "log_id", type = IdType.INPUT)
    private String logId;

    @TableField("version_id")
    private String versionId;

    @TableField("from_stage")
    private String fromStage;

    @TableField("to_stage")
    private String toStage;

    @TableField("reason")
    private String reason;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
