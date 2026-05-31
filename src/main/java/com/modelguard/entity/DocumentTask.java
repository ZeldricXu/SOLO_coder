package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "document_task", autoResultMap = true)
public class DocumentTask extends BaseEntity {

    @TableField("task_id")
    private String taskId;

    @TableField("pipeline_id")
    private String pipelineId;

    @TableField("file_name")
    private String fileName;

    @TableField("file_path")
    private String filePath;

    @TableField("file_size")
    private Long fileSize;

    @TableField("status")
    private String status;

    @TableField("phase")
    private String phase;

    @TableField("progress")
    private BigDecimal progress;

    @TableField("total_chunks")
    private Integer totalChunks;

    @TableField("vector_store")
    private String vectorStore;

    @TableField("error_detail")
    private String errorDetail;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
