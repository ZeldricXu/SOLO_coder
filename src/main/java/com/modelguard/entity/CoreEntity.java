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
@TableName(value = "core_entity", autoResultMap = true)
public class CoreEntity extends BaseEntity {

    @TableField("entity_id")
    private String entityId;

    @TableField("type")
    private String type;

    @TableField("status")
    private String status;

    @TableField(value = "attributes", typeHandler = JacksonTypeHandler.class)
    private ObjectNode attributes;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
