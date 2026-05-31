package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "document_pipeline", autoResultMap = true)
public class DocumentPipeline extends BaseEntity {

    @TableField("pipeline_id")
    private String pipelineId;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("source_type")
    private String sourceType;

    @TableField("chunk_size")
    private Integer chunkSize;

    @TableField("chunk_overlap")
    private Integer chunkOverlap;

    @TableField("embedding_model")
    private String embeddingModel;

    @TableField("vector_dimension")
    private Integer vectorDimension;

    @TableField("status")
    private String status;

    @TableField("created_by")
    private String createdBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
