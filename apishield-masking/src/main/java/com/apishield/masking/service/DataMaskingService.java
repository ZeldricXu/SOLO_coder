package com.apishield.masking.service;

import com.apishield.application.service.ApplicationService;
import com.apishield.domain.vo.UserContext;
import com.apishield.masking.domain.MaskingPolicy;
import com.apishield.masking.dto.MaskingPolicyRequest;
import com.apishield.masking.dto.MaskingRequest;
import java.util.List;
import java.util.Map;

public interface DataMaskingService extends ApplicationService {
    MaskingPolicy createPolicy(MaskingPolicyRequest request);
    MaskingPolicy getPolicy(String policyId);
    List<MaskingPolicy> getAllPolicies();
    List<MaskingPolicy> getPoliciesForTable(String dataSource, String tableName);
    MaskingPolicy updatePolicy(String policyId, MaskingPolicyRequest request);
    void deletePolicy(String policyId);
    MaskingPolicy enablePolicy(String policyId);
    MaskingPolicy disablePolicy(String policyId);
    
    Map<String, Object> maskData(MaskingRequest request);
    Object maskValue(String dataSource, String tableName, String columnName, Object value, UserContext userContext);
    boolean shouldMask(String dataSource, String tableName, String columnName, UserContext userContext);
    List<MaskingPolicy> getApplicablePolicies(String dataSource, String tableName, UserContext userContext);
}
