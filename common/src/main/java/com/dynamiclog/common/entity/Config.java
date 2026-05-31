package com.dynamiclog.common.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class Config extends BaseEntity {
    private String configId;
    private String namespace;
    private String group;
    private String dataId;
    private Integer version;
    private Map<String, Object> parameters;
    private String content;
    private String contentType;
    private Boolean enabled;
    private String description;
    private String appliedBy;
    private LocalDateTime appliedAt;
    private Boolean rollbackAvailable;
    private Integer previousVersion;
}
