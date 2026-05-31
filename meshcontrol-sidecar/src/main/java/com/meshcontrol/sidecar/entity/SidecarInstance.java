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
@TableName(value = "sidecar_instance", autoResultMap = true)
public class SidecarInstance extends BaseEntity {

    private String sidecarId;
    private String podName;
    private String namespace;
    private String nodeName;
    private String serviceName;
    private String version;
    private String status;
    private Integer configVersion;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> resources;

    private LocalDateTime injectedAt;
    private LocalDateTime lastHeartbeat;
}
