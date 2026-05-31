package com.apishield.masking.domain;

import com.apishield.domain.entity.BaseEntity;
import com.apishield.domain.vo.SecurityLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class MaskingPolicy extends BaseEntity {
    private String policyId;
    private String policyName;
    private String description;
    private String dataSource;
    private String tableName;
    private String columnName;
    private SecurityLevel minClearanceLevel;
    private MaskingStrategyType strategyType;
    private List<String> allowedRoles;
    private boolean enabled;
    private int priority;

    public MaskingPolicy() {
        this.allowedRoles = new ArrayList<>();
    }

    public enum MaskingStrategyType {
        FULL_MASK, PARTIAL_MASK, HASH, NULLIFY, REPLACE, RANDOM
    }
}
