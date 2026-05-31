package com.llmgateway.modelregistry.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("model_version")
public class ModelVersion implements Serializable {

    @TableId(value = "version_id", type = IdType.INPUT)
    private String versionId;

    @TableField("model_id")
    private String modelId;

    @TableField("version")
    private String version;

    @TableField("stage")
    private String stage;

    @TableField("description")
    private String description;

    @TableField("artifact_path")
    private String artifactPath;

    @TableField(value = "metrics", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> metrics;

    @TableField(value = "parameters", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> parameters;

    @TableField("dataset")
    private String dataset;

    @TableField("commit_hash")
    private String commitHash;

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
