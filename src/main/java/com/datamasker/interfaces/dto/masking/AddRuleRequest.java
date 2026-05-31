package com.datamasker.interfaces.dto.masking;

import lombok.Data;

@Data
public class AddRuleRequest {

    private String fieldPattern;

    private String strategy;

    private String levelRequired;

    private String params;
}
