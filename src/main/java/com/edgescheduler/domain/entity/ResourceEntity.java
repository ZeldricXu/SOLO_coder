package com.edgescheduler.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.edgescheduler.domain.enums.ResourceStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("resource")
public class ResourceEntity extends BaseEntity {

    @TableField("entity_id")
    private String entityId;

    @TableField("type")
    private String type;

    @TableField("status")
    private ResourceStatus status;

    @TableField(value = "attributes", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> attributes;

    @TableField("config_id")
    private String configId;

    @TableField("namespace")
    private String namespace;
}
