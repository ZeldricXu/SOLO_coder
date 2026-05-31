package com.datamasker.domain.masking.model;

import lombok.Data;

@Data
public class MaskingRule {

    private String ruleId;

    private String fieldPattern;

    private MaskingStrategy strategy;

    private String levelRequired;

    private String params;

    private boolean enabled;
}
