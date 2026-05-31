package com.llmgateway.featurestore.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("feature_backfill_job")
public class FeatureBackfillJob implements Serializable {

    @TableId(value = "job_id", type = IdType.INPUT)
    private String jobId;

    @TableField("feature_id")
    private String featureId;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    @TableField("status")
    private String status;

    @TableField("progress")
    private Double progress;

    @TableField("total_count")
    private Long totalCount;

    @TableField("success_count")
    private Long successCount;

    @TableField("failed_count")
    private Long failedCount;

    @TableField("error_detail")
    private String errorDetail;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
