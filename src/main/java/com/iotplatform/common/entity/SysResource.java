package com.iotplatform.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_resource")
public class SysResource extends BaseEntity {

    @TableField("resource_id")
    private String resourceId;

    @TableField("type")
    private String type;

    @TableField("status")
    private String status;

    @TableField("attributes")
    private String attributes;

    @TableField("labels")
    private String labels;

    @TableField("created_by")
    private String createdBy;
}
