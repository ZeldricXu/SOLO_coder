package com.apishield.classification.domain;

import com.apishield.domain.entity.BaseEntity;
import com.apishield.domain.vo.SecurityLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.HashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class ClassificationPolicy extends BaseEntity {
    private String policyId;
    private String policyName;
    private String description;
    private SecurityLevel defaultLevel;
    private Map<String, SecurityLevel> categoryLevelMap;
    private Map<String, String> rules;
    private boolean enabled;
    private int priority;

    public ClassificationPolicy() {
        this.categoryLevelMap = new HashMap<>();
        this.rules = new HashMap<>();
    }
}
