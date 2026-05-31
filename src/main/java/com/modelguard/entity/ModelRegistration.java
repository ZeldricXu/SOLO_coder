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
@TableName(value = "model_registration", autoResultMap = true)
public class ModelRegistration extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String modelId;

    private String modelName;

    private String modelType;

    private String description;

    private String owner;

    private String department;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> tags;

    private String currentStage;

    private String latestVersion;

    private String status;

    private LocalDateTime registeredAt;

    private LocalDateTime lastModifiedAt;

    private String license;

    private String repository;

    private String documentationUrl;
}
