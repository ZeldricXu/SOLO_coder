package com.ratelimiter.controller;

import com.ratelimiter.model.RateLimitPolicy;
import com.ratelimiter.model.dto.ApiResponse;
import com.ratelimiter.model.dto.PolicyConfigResponse;
import com.ratelimiter.service.policy.PolicyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/policy")
public class PolicyController {
    
    private final PolicyService policyService;
    
    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }
    
    @PutMapping("/config")
    public ResponseEntity<ApiResponse<PolicyConfigResponse>> createOrUpdatePolicy(
            @RequestBody RateLimitPolicy policy) {
        
        log.info("Creating/Updating policy: {}", policy.getPolicyId());
        
        RateLimitPolicy savedPolicy = policyService.createPolicy(policy);
        
        PolicyConfigResponse response = PolicyConfigResponse.builder()
                .policyId(savedPolicy.getPolicyId())
                .updatedAt(Instant.now())
                .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    @GetMapping("/{policyId}")
    public ResponseEntity<ApiResponse<RateLimitPolicy>> getPolicy(@PathVariable String policyId) {
        RateLimitPolicy policy = policyService.getPolicy(policyId);
        
        if (policy == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "Policy not found"));
        }
        
        return ResponseEntity.ok(ApiResponse.success(policy));
    }
    
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<RateLimitPolicy>> findPolicyByTarget(
            @RequestParam String targetType,
            @RequestParam String targetValue) {
        
        RateLimitPolicy policy = policyService.getPolicyByTarget(targetType, targetValue);
        
        if (policy == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "Policy not found"));
        }
        
        return ResponseEntity.ok(ApiResponse.success(policy));
    }
    
    @GetMapping("/all")
    public ResponseEntity<ApiResponse<List<RateLimitPolicy>>> getAllPolicies() {
        List<RateLimitPolicy> policies = policyService.getAllPolicies();
        return ResponseEntity.ok(ApiResponse.success(policies));
    }
    
    @DeleteMapping("/{policyId}")
    public ResponseEntity<ApiResponse<Void>> deletePolicy(@PathVariable String policyId) {
        log.info("Deleting policy: {}", policyId);
        policyService.deletePolicy(policyId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
    
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Void>> refreshPolicyCache(
            @RequestParam(value = "policyId", required = false) String policyId) {
        
        if (policyId != null && !policyId.isEmpty()) {
            policyService.refreshPolicyCache(policyId);
        } else {
            policyService.refreshAllPolicyCache();
        }
        
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}