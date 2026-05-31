package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("config_definition")
public class ConfigDefinition extends BaseEntity {

    private String configId;
    private String namespace;
    private Integer version;
    private Map<String, Object> parameters;
    private Boolean enabled;
    private LocalDateTime appliedAt;

    @TableField("version_lock")
    private Integer versionLock;
}
