package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "model_version", autoResultMap = true)
public class ModelVersion extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String modelId;

    private String version;

    private Integer versionNumber;

    private String stage;

    private String parentVersion;

    private String description;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metrics;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> artifacts;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> trainingData;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> hyperparameters;

    private String algorithm;

    private String framework;

    private String frameworkVersion;

    private String status;

    private String createdBy;

    private LocalDateTime createdTime;

    private LocalDateTime deployedAt;

    private LocalDateTime archivedAt;

    private String checksum;

    private Long modelSizeBytes;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> environment;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> dependencies;

    private String notes;

    private String approvalStatus;

    private String approvedBy;

    private LocalDateTime approvedAt;
}
