package com.scheduler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.scheduler.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("config_definitions")
public class ConfigDefinition extends BaseEntity {
    private String configId;
    private String namespace;
    private Integer version;
    private Map<String, Object> parameters;
    private Boolean enabled;
    private Instant appliedAt;
    private String appliedBy;
    private String description;
    private Map<String, String> labels;
}
