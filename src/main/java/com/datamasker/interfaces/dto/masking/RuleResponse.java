package com.datamasker.interfaces.dto.masking;

import lombok.Data;

@Data
public class RuleResponse {

    private String ruleId;

    private String fieldPattern;

    private String strategy;

    private String levelRequired;

    private String params;

    private boolean enabled;
}
