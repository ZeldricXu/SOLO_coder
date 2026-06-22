package com.enterprise.gateway.common;

import com.enterprise.gateway.common.model.RateLimitRule;

public class RateLimitRuleBuilder {

    private String routeId = "test-route";
    private String strategy = "TOKEN_BUCKET";
    private Long capacity = 100L;
    private Long refillRate = 10L;
    private Long windowSize = 60L;
    private Long permits = 100L;
    private Integer status = 1;

    private RateLimitRuleBuilder() {
    }

    public static RateLimitRuleBuilder builder() {
        return new RateLimitRuleBuilder();
    }

    public RateLimitRuleBuilder withRouteId(String routeId) {
        this.routeId = routeId;
        return this;
    }

    public RateLimitRuleBuilder withStrategy(String strategy) {
        this.strategy = strategy;
        return this;
    }

    public RateLimitRuleBuilder withCapacity(Long capacity) {
        this.capacity = capacity;
        return this;
    }

    public RateLimitRuleBuilder withRefillRate(Long refillRate) {
        this.refillRate = refillRate;
        return this;
    }

    public RateLimitRuleBuilder withWindowSize(Long windowSize) {
        this.windowSize = windowSize;
        return this;
    }

    public RateLimitRuleBuilder withPermits(Long permits) {
        this.permits = permits;
        return this;
    }

    public RateLimitRuleBuilder withStatus(Integer status) {
        this.status = status;
        return this;
    }

    public RateLimitRule build() {
        return RateLimitRule.builder()
                .routeId(routeId)
                .strategy(strategy)
                .capacity(capacity)
                .refillRate(refillRate)
                .windowSize(windowSize)
                .permits(permits)
                .status(status)
                .build();
    }
}
