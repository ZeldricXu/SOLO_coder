package com.memberscore.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memberscore.entity.ExpirePolicyConfig;
import com.memberscore.entity.Member;
import com.memberscore.entity.PointRule;
import com.memberscore.enums.ExpirePolicyType;
import com.memberscore.repository.ExpirePolicyConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExpirePolicyService {
    
    private final ExpirePolicyConfigRepository expirePolicyConfigRepository;
    private final ObjectMapper objectMapper;
    
    @Value("${points.expire.days:365}")
    private int defaultExpireDays;
    
    @Transactional(readOnly = true)
    public Optional<ExpirePolicyConfig> getDefaultPolicy() {
        return expirePolicyConfigRepository.findByIsDefaultTrueAndIsEnabledTrue();
    }
    
    @Transactional(readOnly = true)
    public Optional<ExpirePolicyConfig> getPolicyById(String policyId) {
        return expirePolicyConfigRepository.findByPolicyIdAndIsEnabledTrue(policyId);
    }
    
    @Transactional(readOnly = true)
    public List<ExpirePolicyConfig> getAllEnabledPolicies() {
        return expirePolicyConfigRepository.findByIsEnabledTrue();
    }
    
    @Transactional(readOnly = true)
    public List<ExpirePolicyConfig> getPoliciesByType(ExpirePolicyType type) {
        return expirePolicyConfigRepository.findByPolicyTypeAndIsEnabledTrue(type);
    }
    
    @Transactional
    public ExpirePolicyConfig createPolicy(ExpirePolicyConfig policy) {
        if (policy.getPolicyId() == null) {
            policy.setPolicyId("exp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        }
        
        if (Boolean.TRUE.equals(policy.getIsDefault())) {
            getDefaultPolicy().ifPresent(existing -> {
                existing.setIsDefault(false);
                existing.setUpdatedAt(LocalDateTime.now());
                expirePolicyConfigRepository.save(existing);
            });
        }
        
        ExpirePolicyConfig saved = expirePolicyConfigRepository.save(policy);
        log.info("创建过期策略成功: policyId={}, policyType={}", 
                saved.getPolicyId(), saved.getPolicyType());
        return saved;
    }
    
    @Transactional
    public ExpirePolicyConfig updatePolicy(String policyId, ExpirePolicyConfig updatedPolicy) {
        ExpirePolicyConfig existing = expirePolicyConfigRepository.findByPolicyId(policyId)
                .orElseThrow(() -> new RuntimeException("过期策略不存在: " + policyId));
        
        existing.setPolicyName(updatedPolicy.getPolicyName());
        existing.setPolicyType(updatedPolicy.getPolicyType());
        existing.setFixedExpireDays(updatedPolicy.getFixedExpireDays());
        existing.setFlexibleBaseDays(updatedPolicy.getFlexibleBaseDays());
        existing.setFlexibleMaxDays(updatedPolicy.getFlexibleMaxDays());
        existing.setLevelExpireConfig(updatedPolicy.getLevelExpireConfig());
        existing.setPointThreshold(updatedPolicy.getPointThreshold());
        existing.setIsEnabled(updatedPolicy.getIsEnabled());
        existing.setDescription(updatedPolicy.getDescription());
        existing.setUpdatedAt(LocalDateTime.now());
        
        if (Boolean.TRUE.equals(updatedPolicy.getIsDefault()) && !Boolean.TRUE.equals(existing.getIsDefault())) {
            getDefaultPolicy().ifPresent(def -> {
                def.setIsDefault(false);
                def.setUpdatedAt(LocalDateTime.now());
                expirePolicyConfigRepository.save(def);
            });
            existing.setIsDefault(true);
        }
        
        ExpirePolicyConfig saved = expirePolicyConfigRepository.save(existing);
        log.info("更新过期策略成功: policyId={}", saved.getPolicyId());
        return saved;
    }
    
    @Transactional
    public void setDefaultPolicy(String policyId) {
        ExpirePolicyConfig policy = expirePolicyConfigRepository.findByPolicyId(policyId)
                .orElseThrow(() -> new RuntimeException("过期策略不存在: " + policyId));
        
        getDefaultPolicy().ifPresent(existing -> {
            existing.setIsDefault(false);
            existing.setUpdatedAt(LocalDateTime.now());
            expirePolicyConfigRepository.save(existing);
        });
        
        policy.setIsDefault(true);
        policy.setUpdatedAt(LocalDateTime.now());
        expirePolicyConfigRepository.save(policy);
        
        log.info("设置默认过期策略: policyId={}", policyId);
    }
    
    public LocalDate calculateExpireDate(
            PointRule pointRule, 
            Member member, 
            int earnedPoints) {
        
        String policyId = pointRule.getExpirePolicyId();
        ExpirePolicyConfig policy = null;
        
        if (policyId != null && !policyId.isEmpty()) {
            policy = getPolicyById(policyId).orElse(null);
        }
        
        if (policy == null) {
            policy = getDefaultPolicy().orElse(null);
        }
        
        if (policy == null) {
            log.debug("未找到过期策略配置，使用默认过期天数: {}天", defaultExpireDays);
            return LocalDate.now().plusDays(defaultExpireDays);
        }
        
        switch (policy.getPolicyType()) {
            case FIXED_TERM:
                return calculateFixedTermExpire(policy);
                
            case FLEXIBLE_TERM:
                return calculateFlexibleTermExpire(policy, earnedPoints);
                
            case LEVEL_DIFFERENCE:
                return calculateLevelDifferenceExpire(policy, member);
                
            case NEVER_EXPIRE:
                return null;
                
            default:
                return LocalDate.now().plusDays(defaultExpireDays);
        }
    }
    
    private LocalDate calculateFixedTermExpire(ExpirePolicyConfig policy) {
        int days = policy.getFixedExpireDays() != null 
                ? policy.getFixedExpireDays() 
                : defaultExpireDays;
        return LocalDate.now().plusDays(days);
    }
    
    private LocalDate calculateFlexibleTermExpire(ExpirePolicyConfig policy, int earnedPoints) {
        int baseDays = policy.getFlexibleBaseDays() != null 
                ? policy.getFlexibleBaseDays() 
                : defaultExpireDays;
        int maxDays = policy.getFlexibleMaxDays() != null 
                ? policy.getFlexibleMaxDays() 
                : baseDays * 2;
        int threshold = policy.getPointThreshold() != null 
                ? policy.getPointThreshold() 
                : 1000;
        
        double ratio = Math.min(1.0, (double) earnedPoints / threshold);
        int additionalDays = (int) ((maxDays - baseDays) * ratio);
        int totalDays = baseDays + additionalDays;
        
        log.debug("灵活期限计算: earnedPoints={}, baseDays={}, additionalDays={}, totalDays={}", 
                earnedPoints, baseDays, additionalDays, totalDays);
        
        return LocalDate.now().plusDays(totalDays);
    }
    
    private LocalDate calculateLevelDifferenceExpire(ExpirePolicyConfig policy, Member member) {
        Map<String, Integer> levelConfig = parseLevelExpireConfig(policy.getLevelExpireConfig());
        
        String levelId = member.getMemberLevel();
        Integer days = levelConfig.get(levelId);
        
        if (days == null) {
            days = policy.getFixedExpireDays() != null 
                    ? policy.getFixedExpireDays() 
                    : defaultExpireDays;
        }
        
        log.debug("等级差异期限计算: levelId={}, expireDays={}", levelId, days);
        
        return LocalDate.now().plusDays(days);
    }
    
    private Map<String, Integer> parseLevelExpireConfig(String configJson) {
        Map<String, Integer> config = new HashMap<>();
        if (configJson == null || configJson.isEmpty()) {
            return config;
        }
        
        try {
            config = objectMapper.readValue(
                    configJson, 
                    new TypeReference<Map<String, Integer>>() {});
        } catch (Exception e) {
            log.warn("解析等级过期配置失败", e);
        }
        
        return config;
    }
    
    public boolean shouldExpirePoints(PointRule pointRule) {
        String policyId = pointRule.getExpirePolicyId();
        if (policyId != null && !policyId.isEmpty()) {
            Optional<ExpirePolicyConfig> policy = getPolicyById(policyId);
            if (policy.isPresent() && policy.get().getPolicyType() == ExpirePolicyType.NEVER_EXPIRE) {
                return false;
            }
        }
        
        Optional<ExpirePolicyConfig> defaultPolicy = getDefaultPolicy();
        if (defaultPolicy.isPresent() && defaultPolicy.get().getPolicyType() == ExpirePolicyType.NEVER_EXPIRE) {
            return false;
        }
        
        return true;
    }
}
