package com.chaoslab.modules.sidecar.dto;

import lombok.Data;

@Data
public class ConfigApplyRequest {
    private String instanceId;
    private String templateId;
    private String appliedBy;
    private String reason;
}
