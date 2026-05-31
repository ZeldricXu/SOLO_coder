package com.edgescheduler.ruleengine.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class RuleTriggerRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String ruleId;
    private String deviceKey;
    private Map<String, Object> triggerData;
    private String eventType;
    private String traceId;
}
