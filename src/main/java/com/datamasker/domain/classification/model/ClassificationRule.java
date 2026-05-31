package com.datamasker.domain.classification.model;

import lombok.Data;

@Data
public class ClassificationRule {

    private String ruleId;

    private String name;

    private String category;

    private String pattern;

    private String level;

    private boolean enabled;
}
