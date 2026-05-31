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
@TableName("model_versions")
public class ModelVersion extends BaseEntity {

    @TableField("version_id")
    private String versionId;

    @TableField("model_id")
    private String modelId;

    @TableField("version")
    private String version;

    @TableField("stage")
    private StageType stage;

    @TableField("artifact_path")
    private String artifactPath;

    @TableField("checksum")
    private String checksum;

    @TableField("size_bytes")
    private Long sizeBytes;

    @TableField("metrics")
    private String metrics;

    @TableField("training_data")
    private String trainingData;

    @TableField("description")
    private String description;

    @TableField("created_by")
    private String createdBy;

    @TableField("promoted_at")
    private LocalDateTime promotedAt;

    @TableField("archived_at")
    private LocalDateTime archivedAt;
}
