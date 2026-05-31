package com.observability.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_resource")
public class ResourceEntity extends BaseEntity {

    private String resourceId;

    private String type;

    private String status;

    private Map<String, Object> attributes;

    private String namespace;

    private String config;

    private String labels;
}
