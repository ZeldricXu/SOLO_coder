package com.taskflow.data.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskflow.common.model.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("resource")
public class ResourceEntity extends TenantEntity {

    @TableField("resource_id")
    private String resourceId;

    @TableField("type")
    private String type;

    @TableField("status")
    private String status;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("attributes")
    private String attributes;

    @TableField("labels")
    private String labels;

    @TableField("config_id")
    private String configId;

    @TableField(exist = false)
    private Map<String, Object> attributesMap;

    @TableField(exist = false)
    private Map<String, String> labelsMap;
}
