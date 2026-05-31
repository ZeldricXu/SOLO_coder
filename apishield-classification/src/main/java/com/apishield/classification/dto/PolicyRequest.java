package com.apishield.classification.dto;

import com.apishield.domain.vo.SecurityLevel;
import lombok.Data;
import java.util.Map;

@Data
public class PolicyRequest {
    private String policyName;
    private String description;
    private SecurityLevel defaultLevel;
    private Map<String, SecurityLevel> categoryLevelMap;
    private Map<String, String> rules;
    private int priority;
}
