package com.taskplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskplatform.common.entity.BaseEntity;
import com.taskplatform.common.enums.StageType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("models")
public class ModelEntity extends BaseEntity {

    @TableField("model_id")
    private String modelId;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("latest_version")
    private String latestVersion;

    @TableField("stage")
    private StageType stage;

    @TableField("model_type")
    private String modelType;

    @TableField("framework")
    private String framework;

    @TableField("metadata")
    private String metadata;

    @TableField("tags")
    private String tags;

    @TableField("created_by")
    private String createdBy;

    @TableField("archived_at")
    private LocalDateTime archivedAt;
}
