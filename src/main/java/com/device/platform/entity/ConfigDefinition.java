package com.device.platform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("config_definition")
public class ConfigDefinition extends BaseEntity {
    private String configId;
    private String namespace;
    private Integer version;
    private String parameters;
    private boolean enabled;
    private Instant appliedAt;
    private String appliedBy;
    private String description;
}
