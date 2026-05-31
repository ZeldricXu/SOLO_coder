package com.apishield.masking.service.impl;

import com.apishield.common.exception.BusinessException;
import com.apishield.common.util.IdGenerator;
import com.apishield.domain.vo.SecurityLevel;
import com.apishield.domain.vo.UserContext;
import com.apishield.masking.domain.MaskingPolicy;
import com.apishield.masking.dto.MaskingPolicyRequest;
import com.apishield.masking.dto.MaskingRequest;
import com.apishield.masking.service.DataMaskingService;
import com.apishield.masking.strategy.MaskingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataMaskingServiceImpl implements DataMaskingService {

    private final List<MaskingStrategy> strategies;
    private final Map<String, MaskingPolicy> policyStore = new ConcurrentHashMap<>();

    @Override
    public MaskingPolicy createPolicy(MaskingPolicyRequest request) {
        MaskingPolicy policy = new MaskingPolicy();
        policy.setId(IdGenerator.generateId("mask"));
        policy.setPolicyId(policy.getId());
        policy.setPolicyName(request.getPolicyName());
        policy.setDescription(request.getDescription());
        policy.setDataSource(request.getDataSource());
        policy.setTableName(request.getTableName());
        policy.setColumnName(request.getColumnName());
        policy.setMinClearanceLevel(request.getMinClearanceLevel() != null ? 
                request.getMinClearanceLevel() : SecurityLevel.CONFIDENTIAL);
        policy.setStrategyType(request.getStrategyType());
        policy.setPriority(request.getPriority());
        policy.setEnabled(true);
        policy.setCreatedAt(LocalDateTime.now());
        policy.setUpdatedAt(LocalDateTime.now());

        if (request.getAllowedRoles() != null) {
            policy.setAllowedRoles(request.getAllowedRoles());
        }

        policyStore.put(policy.getPolicyId(), policy);
        log.info("Created masking policy: {} for {}.{}", 
                policy.getPolicyId(), request.getTableName(), request.getColumnName());
        return policy;
    }

    @Override
    public MaskingPolicy getPolicy(String policyId) {
        MaskingPolicy policy = policyStore.get(policyId);
        if (policy == null) {
            throw new BusinessException("NOT_FOUND", "脱敏策略不存在: " + policyId);
        }
        return policy;
    }

    @Override
    public List<MaskingPolicy> getAllPolicies() {
        return new ArrayList<>(policyStore.values());
    }

    @Override
    public List<MaskingPolicy> getPoliciesForTable(String dataSource, String tableName) {
        return policyStore.values().stream()
                .filter(p -> dataSource.equals(p.getDataSource()) && tableName.equals(p.getTableName()))
                .sorted(Comparator.comparingInt(MaskingPolicy::getPriority))
                .collect(Collectors.toList());
    }

    @Override
    public MaskingPolicy updatePolicy(String policyId, MaskingPolicyRequest request) {
        MaskingPolicy policy = getPolicy(policyId);
        policy.setPolicyName(request.getPolicyName());
        policy.setDescription(request.getDescription());
        policy.setDataSource(request.getDataSource());
        policy.setTableName(request.getTableName());
        policy.setColumnName(request.getColumnName());
        if (request.getMinClearanceLevel() != null) {
            policy.setMinClearanceLevel(request.getMinClearanceLevel());
        }
        policy.setStrategyType(request.getStrategyType());
        policy.setPriority(request.getPriority());
        policy.setUpdatedAt(LocalDateTime.now());

        if (request.getAllowedRoles() != null) {
            policy.setAllowedRoles(request.getAllowedRoles());
        }

        return policy;
    }

    @Override
    public void deletePolicy(String policyId) {
        policyStore.remove(policyId);
        log.info("Deleted masking policy: {}", policyId);
    }

    @Override
    public MaskingPolicy enablePolicy(String policyId) {
        MaskingPolicy policy = getPolicy(policyId);
        policy.setEnabled(true);
        policy.setUpdatedAt(LocalDateTime.now());
        return policy;
    }

    @Override
    public MaskingPolicy disablePolicy(String policyId) {
        MaskingPolicy policy = getPolicy(policyId);
        policy.setEnabled(false);
        policy.setUpdatedAt(LocalDateTime.now());
        return policy;
    }

    @Override
    public Map<String, Object> maskData(MaskingRequest request) {
        Map<String, Object> maskedData = new HashMap<>(request.getData());
        List<MaskingPolicy> applicablePolicies = getApplicablePolicies(
                request.getDataSource(), 
                request.getTableName(), 
                request.getUserContext());

        for (MaskingPolicy policy : applicablePolicies) {
            String columnName = policy.getColumnName();
            if (maskedData.containsKey(columnName)) {
                Object originalValue = maskedData.get(columnName);
                Object maskedValue = maskValue(policy, originalValue);
                maskedData.put(columnName, maskedValue);
                log.debug("Masked column {} with strategy {}", columnName, policy.getStrategyType());
            }
        }

        return maskedData;
    }

    @Override
    public Object maskValue(String dataSource, String tableName, String columnName, Object value, UserContext userContext) {
        List<MaskingPolicy> policies = getApplicablePolicies(dataSource, tableName, userContext);
        for (MaskingPolicy policy : policies) {
            if (columnName.equals(policy.getColumnName())) {
                return maskValue(policy, value);
            }
        }
        return value;
    }

    @Override
    public boolean shouldMask(String dataSource, String tableName, String columnName, UserContext userContext) {
        return !getApplicablePolicies(dataSource, tableName, userContext).stream()
                .filter(p -> columnName.equals(p.getColumnName()))
                .collect(Collectors.toList())
                .isEmpty();
    }

    @Override
    public List<MaskingPolicy> getApplicablePolicies(String dataSource, String tableName, UserContext userContext) {
        return policyStore.values().stream()
                .filter(MaskingPolicy::isEnabled)
                .filter(p -> dataSource.equals(p.getDataSource()) && tableName.equals(p.getTableName()))
                .filter(p -> !hasSufficientClearance(p, userContext))
                .sorted(Comparator.comparingInt(MaskingPolicy::getPriority))
                .collect(Collectors.toList());
    }

    private boolean hasSufficientClearance(MaskingPolicy policy, UserContext userContext) {
        if (userContext == null) {
            return false;
        }

        if (policy.getAllowedRoles() != null && !policy.getAllowedRoles().isEmpty()) {
            if (userContext.getRole() != null && policy.getAllowedRoles().contains(userContext.getRole())) {
                return true;
            }
            if (userContext.getPermissions() != null && 
                policy.getAllowedRoles().stream().anyMatch(r -> userContext.getPermissions().contains(r))) {
                return true;
            }
        }

        if (policy.getMinClearanceLevel() != null && userContext.getClearanceLevel() != null) {
            return userContext.getClearanceLevel().ordinal() >= policy.getMinClearanceLevel().ordinal();
        }

        return false;
    }

    private Object maskValue(MaskingPolicy policy, Object value) {
        if (value == null) {
            return null;
        }

        MaskingStrategy strategy = strategies.stream()
                .filter(s -> s.getStrategyType() == policy.getStrategyType())
                .findFirst()
                .orElseThrow(() -> new BusinessException("MASK_001", 
                    "不支持的脱敏策略: " + policy.getStrategyType()));

        return strategy.mask(value, policy);
    }
}
