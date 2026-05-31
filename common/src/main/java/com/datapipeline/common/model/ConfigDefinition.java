package com.datapipeline.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigDefinition {

    private String configId;
    private String namespace;
    private int version;
    @Builder.Default
    private Map<String, Object> parameters = new HashMap<>();
    private boolean enabled;
    private Instant appliedAt;

}
