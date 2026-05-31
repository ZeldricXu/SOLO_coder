package com.apishield.masking.dto;

import com.apishield.domain.vo.SecurityLevel;
import com.apishield.masking.domain.MaskingPolicy;
import lombok.Data;
import java.util.List;

@Data
public class MaskingPolicyRequest {
    private String policyName;
    private String description;
    private String dataSource;
    private String tableName;
    private String columnName;
    private SecurityLevel minClearanceLevel;
    private MaskingPolicy.MaskingStrategyType strategyType;
    private List<String> allowedRoles;
    private int priority;
}
