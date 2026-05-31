package com.edgescheduler.modules.inference.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.edgescheduler.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_model")
public class AiModel extends BaseEntity {

    @TableField("model_id")
    private String modelId;

    @TableField("model_name")
    private String modelName;

    @TableField("model_version")
    private String modelVersion;

    @TableField("model_type")
    private String modelType;

    @TableField("model_path")
    private String modelPath;

    @TableField("model_size")
    private Long modelSize;

    @TableField(value = "model_config", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> modelConfig;

    @TableField("deploy_status")
    private String deployStatus;

    @TableField("deployed_devices")
    private Integer deployedDevices;

    @TableField("deployed_at")
    private LocalDateTime deployedAt;

    @TableField("parent_model_id")
    private String parentModelId;

    @TableField("version_status")
    private String versionStatus;

    @TableField("version_description")
    private String versionDescription;

    @TableField("change_log")
    private String changeLog;

    @TableField(value = "compatibility_check", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> compatibilityCheck;

    @TableField("trained_at")
    private LocalDateTime trainedAt;

    @TableField("training_dataset")
    private String trainingDataset;

    @TableField(value = "accuracy_metrics", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> accuracyMetrics;

    @TableField("is_default_version")
    private Boolean isDefaultVersion;

    @TableField("deprecated")
    private Boolean deprecated;

    @TableField("deprecated_at")
    private LocalDateTime deprecatedAt;

    @TableField("deprecated_reason")
    private String deprecatedReason;
}
