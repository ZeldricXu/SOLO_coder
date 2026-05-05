package com.ratelimiter.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.model.RateLimitPolicy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Repository
public class PolicyRepository {
    
    private static final String POLICY_KEY_PREFIX = "ratelimiter:policy:";
    private static final String POLICY_INDEX_KEY = "ratelimiter:policies:index";
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    
    public PolicyRepository(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }
    
    public void save(RateLimitPolicy policy) {
        String key = getPolicyKey(policy.getPolicyId());
        redisTemplate.opsForValue().set(key, policy);
        redisTemplate.opsForSet().add(POLICY_INDEX_KEY, policy.getPolicyId());
    }
    
    public RateLimitPolicy findById(String policyId) {
        String key = getPolicyKey(policyId);
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        return objectMapper.convertValue(value, RateLimitPolicy.class);
    }
    
    public RateLimitPolicy findByTarget(String targetType, String targetValue) {
        Set<Object> policyIds = redisTemplate.opsForSet().members(POLICY_INDEX_KEY);
        if (policyIds == null) {
            return null;
        }
        
        for (Object policyIdObj : policyIds) {
            String policyId = (String) policyIdObj;
            RateLimitPolicy policy = findById(policyId);
            if (policy != null && 
                policy.getTargetType() != null && 
                policy.getTargetType().equalsIgnoreCase(targetType) &&
                policy.getTargetValue() != null &&
                policy.getTargetValue().equals(targetValue)) {
                return policy;
            }
        }
        return null;
    }
    
    public List<RateLimitPolicy> findAll() {
        List<RateLimitPolicy> policies = new ArrayList<>();
        Set<Object> policyIds = redisTemplate.opsForSet().members(POLICY_INDEX_KEY);
        if (policyIds == null) {
            return policies;
        }
        
        for (Object policyIdObj : policyIds) {
            String policyId = (String) policyIdObj;
            RateLimitPolicy policy = findById(policyId);
            if (policy != null) {
                policies.add(policy);
            }
        }
        return policies;
    }
    
    public void deleteById(String policyId) {
        String key = getPolicyKey(policyId);
        redisTemplate.delete(key);
        redisTemplate.opsForSet().remove(POLICY_INDEX_KEY, policyId);
    }
    
    private String getPolicyKey(String policyId) {
        return POLICY_KEY_PREFIX + policyId;
    }
}