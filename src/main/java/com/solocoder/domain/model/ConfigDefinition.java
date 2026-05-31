package com.solocoder.domain.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigDefinition {
    private String configId;
    private String namespace;
    private Integer version;
    private Map<String, Object> parameters;
    private Boolean enabled;
    private Instant appliedAt;
}
