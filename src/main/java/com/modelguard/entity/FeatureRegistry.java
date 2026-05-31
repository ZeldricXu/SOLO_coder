package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "feature_registry", autoResultMap = true)
public class FeatureRegistry extends BaseEntity {

    @TableField("feature_id")
    private String featureId;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("version")
    private Integer version;

    @TableField("data_type")
    private String dataType;

    @TableField("feature_type")
    private String featureType;

    @TableField("entity")
    private String entity;

    @TableField("source")
    private String source;

    @TableField("ttl_seconds")
    private Long ttlSeconds;

    @TableField(value = "schema_def", typeHandler = JacksonTypeHandler.class)
    private ObjectNode schemaDef;

    @TableField("status")
    private String status;

    @TableField("created_by")
    private String createdBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
