package com.meshcontrol.sidecar.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sidecar_config", autoResultMap = true)
public class SidecarConfig extends BaseEntity {

    private String configId;
    private String namespace;
    private Integer version;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> parameters;

    private Boolean enabled;
    private LocalDateTime appliedAt;
}
