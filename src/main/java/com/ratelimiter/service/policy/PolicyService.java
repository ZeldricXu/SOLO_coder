package com.ratelimiter.service.policy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ratelimiter.exception.PolicyValidationException;
import com.ratelimiter.model.RateLimitPolicy;
import com.ratelimiter.repository.PolicyRepository;
import com.ratelimiter.service.policy.PolicyValidator.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PolicyService {
    
    private final PolicyRepository policyRepository;
    private final PolicyValidator policyValidator;
    private final Cache<String, RateLimitPolicy> policyCache;
    
    public PolicyService(PolicyRepository policyRepository, PolicyValidator policyValidator) {
        this.policyRepository = policyRepository;
        this.policyValidator = policyValidator;
        this.policyCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .build();
    }
    
    public RateLimitPolicy createPolicy(RateLimitPolicy policy) {
        applyDefaultValues(policy);
        
        ValidationResult validationResult = policyValidator.validate(policy);
        
        if (!validationResult.isValid()) {
            log.error("Policy validation failed: {}", validationResult.getErrorsAsString());
            throw new PolicyValidationException(validationResult);
        }
        
        if (validationResult.hasWarnings()) {
            log.warn("Policy validation warnings: {}", validationResult.getWarningsAsString());
        }
        
        policyRepository.save(policy);
        policyCache.put(policy.getPolicyId(), policy);
        
        log.info("Created/Updated policy: {}", policy.getPolicyId());
        return policy;
    }
    
    private void applyDefaultValues(RateLimitPolicy policy) {
        if (policy.getPolicyId() == null || policy.getPolicyId().isEmpty()) {
            policy.setPolicyId(generatePolicyId(policy));
        }
        
        if (policy.getResponseCode() <= 0) {
            policy.setResponseCode(429);
        }
        if (policy.getResponseMessage() == null || policy.getResponseMessage().isEmpty()) {
            policy.setResponseMessage("请求过于频繁请稍后重试");
        }
        if (policy.getActionOnLimit() == null || policy.getActionOnLimit().isEmpty()) {
            policy.setActionOnLimit("reject");
        }
    }
    
    public RateLimitPolicy getPolicy(String policyId) {
        RateLimitPolicy cached = policyCache.getIfPresent(policyId);
        if (cached != null) {
            return cached;
        }
        
        RateLimitPolicy policy = policyRepository.findById(policyId);
        if (policy != null) {
            policyCache.put(policyId, policy);
        }
        return policy;
    }
    
    public RateLimitPolicy getPolicyByTarget(String targetType, String targetValue) {
        return policyRepository.findByTarget(targetType, targetValue);
    }
    
    public RateLimitPolicy resolvePolicyForTarget(String target) {
        RateLimitPolicy policy = getPolicyByTarget("api_path", target);
        if (policy != null) {
            return policy;
        }
        
        policy = getDefaultPolicy();
        return policy;
    }
    
    public List<RateLimitPolicy> getAllPolicies() {
        return policyRepository.findAll();
    }
    
    public void deletePolicy(String policyId) {
        policyRepository.deleteById(policyId);
        policyCache.invalidate(policyId);
        log.info("Deleted policy: {}", policyId);
    }
    
    public void refreshPolicyCache(String policyId) {
        policyCache.invalidate(policyId);
        RateLimitPolicy policy = policyRepository.findById(policyId);
        if (policy != null) {
            policyCache.put(policyId, policy);
        }
        log.info("Refreshed policy cache for: {}", policyId);
    }
    
    public void refreshAllPolicyCache() {
        policyCache.invalidateAll();
        List<RateLimitPolicy> policies = policyRepository.findAll();
        for (RateLimitPolicy policy : policies) {
            policyCache.put(policy.getPolicyId(), policy);
        }
        log.info("Refreshed all policy cache, total policies: {}", policies.size());
    }
    
    private String generatePolicyId(RateLimitPolicy policy) {
        return "policy_" + policy.getTargetType() + "_" + 
               policy.getTargetValue().replace("/", "_").replace(":", "_");
    }
    
    private RateLimitPolicy getDefaultPolicy() {
        return RateLimitPolicy.builder()
                .policyId("default_policy")
                .targetType("default")
                .targetValue("*")
                .algorithm("sliding_window")
                .threshold(1000)
                .windowSize(60)
                .burstSize(100)
                .actionOnLimit("reject")
                .responseCode(429)
                .responseMessage("请求过于频繁请稍后重试")
                .build();
    }
}