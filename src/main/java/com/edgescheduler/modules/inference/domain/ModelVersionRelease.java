package com.edgescheduler.modules.inference.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.edgescheduler.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_version_release")
public class ModelVersionRelease extends BaseEntity {

    @TableField("release_id")
    private String releaseId;

    @TableField("model_id")
    private String modelId;

    @TableField("model_version")
    private String modelVersion;

    @TableField("release_type")
    private String releaseType;

    @TableField("release_status")
    private String releaseStatus;

    @TableField("release_notes")
    private String releaseNotes;

    @TableField("grayscale_percentage")
    private Integer grayscalePercentage;

    @TableField(value = "grayscale_devices", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<String> grayscaleDevices;

    @TableField("success_count")
    private Integer successCount;

    @TableField("failure_count")
    private Integer failureCount;

    @TableField("rollback_count")
    private Integer rollbackCount;

    @TableField("scheduled_at")
    private LocalDateTime scheduledAt;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;
}
