package com.solocoder.dns.common.entity;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class ConfigDefinition implements Serializable {
    private String configId;
    private String namespace;
    private Integer version;
    private Map<String, Object> parameters;
    private Boolean enabled;
    private LocalDateTime appliedAt;
}
