package com.modelguard.dto;

import lombok.Data;
import java.util.Map;

@Data
public class StageTransitionDTO {

    private String modelId;

    private String version;

    private String fromStage;

    private String toStage;

    private String reason;

    private Map<String, Object> approvalChecklist;

    private String transitionedBy;
}
