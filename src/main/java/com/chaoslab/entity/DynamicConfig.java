package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dynamic_config")
public class DynamicConfig extends BaseEntity {

    private String configId;
    private String configKey;
    private String configName;
    private String configType;
    private String description;
    private Map<String, Object> configValue;
    private String defaultValue;
    private String validationRule;
    private Boolean enabled;
    private Boolean hotReloadable;
    private String scope;
    private String lastModifiedBy;
    private LocalDateTime lastModifiedAt;
    private Integer version;
}
