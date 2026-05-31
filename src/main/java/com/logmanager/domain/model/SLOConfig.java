package com.logmanager.domain.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class SLOConfig extends BaseEntity {
    private String sloId;
    private String name;
    private String description;
    private String serviceName;
    private Double targetPercentage;
    private Duration window;
    private Map<String, String> sliConfig = new HashMap<>();
    private Map<String, Object> alertingRules = new HashMap<>();
    private Boolean enabled;
}
