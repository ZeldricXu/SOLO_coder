package com.chaoslab.modules.mtls.dto;

import lombok.Data;

@Data
public class StrategySwitchRequest {
    private String strategyName;
    private String operation;
    private String reason;
    private String operator;
}
