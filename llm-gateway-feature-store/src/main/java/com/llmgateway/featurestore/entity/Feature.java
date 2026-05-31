package com.llmgateway.featurestore.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("feature")
public class Feature implements Serializable {

    @TableId(value = "feature_id", type = IdType.INPUT)
    private String featureId;

    @TableField("feature_name")
    private String featureName;

    @TableField("feature_type")
    private String featureType;

    @TableField("description")
    private String description;

    @TableField("entity")
    private String entity;

    @TableField("value_type")
    private String valueType;

    @TableField("ttl")
    private Integer ttl;

    @TableField("version")
    private Integer version;

    @TableField("status")
    private String status;

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
