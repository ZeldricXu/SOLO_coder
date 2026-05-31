package com.taskplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskplatform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("config_entries")
public class ConfigEntry extends BaseEntity {

    @TableField("config_id")
    private String configId;

    @TableField("namespace")
    private String namespace;

    @TableField("config_key")
    private String configKey;

    @TableField("config_value")
    private String configValue;

    @TableField("version")
    private Integer version;

    @TableField("description")
    private String description;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("applied_at")
    private LocalDateTime appliedAt;

    @TableField("applied_by")
    private String appliedBy;

    @TableField("source")
    private String source;
}
