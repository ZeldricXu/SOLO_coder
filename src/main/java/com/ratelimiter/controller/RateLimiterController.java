package com.ratelimiter.controller;

import com.ratelimiter.model.RateLimitPolicy;
import com.ratelimiter.model.dto.ApiResponse;
import com.ratelimiter.model.dto.RateLimitCheckRequest;
import com.ratelimiter.model.dto.RateLimitCheckResponse;
import com.ratelimiter.service.limiter.FixedWindowRateLimiter;
import com.ratelimiter.service.limiter.RateLimitResult;
import com.ratelimiter.service.limiter.SlidingWindowRateLimiter;
import com.ratelimiter.service.limiter.TokenBucketRateLimiter;
import com.ratelimiter.service.policy.PolicyService;
import com.ratelimiter.service.quota.QuotaResult;
import com.ratelimiter.service.quota.QuotaService;
import com.ratelimiter.service.stats.StatisticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/limiter")
public class RateLimiterController {
    
    private final PolicyService policyService;
    private final FixedWindowRateLimiter fixedWindowRateLimiter;
    private final SlidingWindowRateLimiter slidingWindowRateLimiter;
    private final TokenBucketRateLimiter tokenBucketRateLimiter;
    private final QuotaService quotaService;
    private final StatisticsService statisticsService;
    
    public RateLimiterController(PolicyService policyService,
                                  FixedWindowRateLimiter fixedWindowRateLimiter,
                                  SlidingWindowRateLimiter slidingWindowRateLimiter,
                                  TokenBucketRateLimiter tokenBucketRateLimiter,
                                  QuotaService quotaService,
                                  StatisticsService statisticsService) {
        this.policyService = policyService;
        this.fixedWindowRateLimiter = fixedWindowRateLimiter;
        this.slidingWindowRateLimiter = slidingWindowRateLimiter;
        this.tokenBucketRateLimiter = tokenBucketRateLimiter;
        this.quotaService = quotaService;
        this.statisticsService = statisticsService;
    }
    
    @PostMapping("/check")
    public ResponseEntity<ApiResponse<RateLimitCheckResponse>> checkRateLimit(
            @RequestBody RateLimitCheckRequest request) {
        
        long startTime = System.currentTimeMillis();
        String target = request.getTarget();
        String clientId = request.getClientId();
        
        log.info("Rate limit check for target: {}, clientId: {}", target, clientId);
        
        RateLimitPolicy policy = policyService.resolvePolicyForTarget(target);
        if (policy == null) {
            log.warn("No policy found for target: {}, allowing request", target);
            return ResponseEntity.ok(ApiResponse.success(
                    RateLimitCheckResponse.builder()
                            .allowed(true)
                            .remainingQuota(Integer.MAX_VALUE)
                            .message("No policy configured, request allowed")
                            .build()
            ));
        }
        
        QuotaResult quotaResult = quotaService.tryConsumeQuota(clientId, target);
        if (!quotaResult.isAllowed()) {
            log.warn("Quota exceeded for client: {}, target: {}", clientId, target);
            return ResponseEntity.ok(ApiResponse.success(
                    RateLimitCheckResponse.builder()
                            .allowed(false)
                            .remainingQuota(0)
                            .message(quotaResult.getMessage())
                            .build()
            ));
        }
        
        RateLimitResult rateLimitResult = executeRateLimiter(target, policy);
        
        long latencyMs = System.currentTimeMillis() - startTime;
        statisticsService.recordRequest(target, rateLimitResult.isAllowed(), latencyMs);
        
        log.info("Rate limit check result for target: {}, allowed: {}, remaining: {}", 
                target, rateLimitResult.isAllowed(), rateLimitResult.getRemainingQuota());
        
        RateLimitCheckResponse response = RateLimitCheckResponse.builder()
                .allowed(rateLimitResult.isAllowed())
                .remainingQuota(Math.min(rateLimitResult.getRemainingQuota(), quotaResult.getRemainingQuota()))
                .message(rateLimitResult.getMessage())
                .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    private RateLimitResult executeRateLimiter(String target, RateLimitPolicy policy) {
        String algorithm = policy.getAlgorithm() != null ? 
                policy.getAlgorithm().toUpperCase() : "SLIDING_WINDOW";
        
        return switch (algorithm) {
            case "FIXED_WINDOW" -> fixedWindowRateLimiter.tryAcquire(target, policy);
            case "SLIDING_WINDOW" -> slidingWindowRateLimiter.tryAcquire(target, policy);
            case "TOKEN_BUCKET" -> tokenBucketRateLimiter.tryAcquire(target, policy);
            default -> slidingWindowRateLimiter.tryAcquire(target, policy);
        };
    }
}