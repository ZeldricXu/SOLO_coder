package com.iotplatform.config.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("sys_config_history")
public class SysConfigHistory implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("config_id")
    private String configId;

    @TableField("namespace")
    private String namespace;

    @TableField("version")
    private Integer version;

    @TableField("config_key")
    private String configKey;

    @TableField("config_value")
    private String configValue;

    @TableField("description")
    private String description;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("rollback_from_version")
    private Integer rollbackFromVersion;

    @TableField("rolled_back_at")
    private LocalDateTime rolledBackAt;

    @TableField("rolled_back_by")
    private String rolledBackBy;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
