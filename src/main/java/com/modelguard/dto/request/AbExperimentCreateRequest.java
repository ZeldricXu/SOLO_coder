package com.modelguard.dto.request;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class AbExperimentCreateRequest {

    private String name;

    private String description;

    private String promptId;

    private String controlGroupPromptId;

    private Integer controlGroupPromptVersion;

    private String experimentalGroupPromptId;

    private Integer experimentalGroupPromptVersion;

    private BigDecimal trafficSplit;

    private String createdBy;

    private List<String> metrics;

    private Map<String, Object> config;
}
