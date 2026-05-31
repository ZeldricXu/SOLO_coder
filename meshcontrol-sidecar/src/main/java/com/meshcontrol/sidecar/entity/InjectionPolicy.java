package com.meshcontrol.sidecar.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "injection_policy", autoResultMap = true)
public class InjectionPolicy extends BaseEntity {

    private String policyId;
    private String name;
    private String namespace;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> selector;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> sidecarTemplate;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> resources;

    private Boolean enabled;
    private Integer priority;
}
