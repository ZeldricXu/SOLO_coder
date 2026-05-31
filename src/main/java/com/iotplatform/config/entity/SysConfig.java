package com.iotplatform.config.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.iotplatform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_config")
public class SysConfig extends BaseEntity {

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

    @TableField("applied_at")
    private LocalDateTime appliedAt;

    @TableField("created_by")
    private String createdBy;

    @Version
    @TableField(exist = false)
    private Long optimisticLock;
}
