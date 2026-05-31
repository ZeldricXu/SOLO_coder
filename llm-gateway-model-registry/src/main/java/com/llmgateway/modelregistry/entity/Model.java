package com.llmgateway.modelregistry.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("model")
public class Model implements Serializable {

    @TableId(value = "model_id", type = IdType.INPUT)
    private String modelId;

    @TableField("model_name")
    private String modelName;

    @TableField("model_type")
    private String modelType;

    @TableField("provider")
    private String provider;

    @TableField("description")
    private String description;

    @TableField("task_type")
    private String taskType;

    @TableField("base_model")
    private String baseModel;

    @TableField("license")
    private String license;

    @TableField(value = "tags", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, String> tags;

    @TableField("owner")
    private String owner;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
