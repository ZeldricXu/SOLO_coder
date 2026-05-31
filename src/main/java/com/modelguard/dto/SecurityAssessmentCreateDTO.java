package com.modelguard.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class SecurityAssessmentCreateDTO {

    private String modelId;

    private String version;

    private String assessmentType;

    private List<String> attackIds;

    private Map<String, Object> config;

    private String assessedBy;
}
