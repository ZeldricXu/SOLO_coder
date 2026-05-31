package com.logmanager.domain.model;

import com.logmanager.common.enums.ConfigSource;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class ConfigDefinition extends BaseEntity {
    private String configId;
    private String namespace;
    private Integer version;
    private Map<String, Object> parameters = new HashMap<>();
    private Boolean enabled;
    private Instant appliedAt;
    private ConfigSource source;
}
