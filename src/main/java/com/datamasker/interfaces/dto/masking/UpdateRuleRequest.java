package com.datamasker.interfaces.dto.masking;

import lombok.Data;

@Data
public class UpdateRuleRequest {

    private String strategy;

    private String levelRequired;

    private String params;

    private boolean enabled;
}
