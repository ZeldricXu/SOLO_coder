package com.solo.config.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sidecar_instances")
public class SidecarInstance {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("instance_id")
    private String instanceId;

    @TableField("pod_name")
    private String podName;

    private String namespace;

    private String status;

    @TableField("cpu_limit")
    private String cpuLimit;

    @TableField("memory_limit")
    private String memoryLimit;

    @TableField("config_version")
    private Integer configVersion;

    @TableField("last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
