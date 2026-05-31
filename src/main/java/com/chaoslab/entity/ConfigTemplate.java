package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("config_template")
public class ConfigTemplate extends BaseEntity {

    private String templateId;
    private String templateName;
    private String templateType;
    private String scenario;
    private String description;
    private Map<String, Object> configData;
    private Map<String, Object> resourceLimits;
    private Boolean enabled;
    private Integer priority;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer version;
}
