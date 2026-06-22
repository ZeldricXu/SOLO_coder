package com.enterprise.gateway.common;

import com.enterprise.gateway.common.model.CircuitBreakerRule;

public class CircuitBreakerRuleBuilder {

    private String routeId = "test-route";
    private Double failureRateThreshold = 50.0;
    private Double slowCallRateThreshold = 60.0;
    private Long slowCallDurationThreshold = 5000L;
    private Long waitDurationInOpenState = 30000L;
    private Integer permittedNumberOfCallsInHalfOpenState = 10;
    private Integer minimumNumberOfCalls = 20;
    private Integer slidingWindowSize = 100;
    private Integer status = 1;

    private CircuitBreakerRuleBuilder() {
    }

    public static CircuitBreakerRuleBuilder builder() {
        return new CircuitBreakerRuleBuilder();
    }

    public CircuitBreakerRuleBuilder withRouteId(String routeId) {
        this.routeId = routeId;
        return this;
    }

    public CircuitBreakerRuleBuilder withFailureRateThreshold(Double failureRateThreshold) {
        this.failureRateThreshold = failureRateThreshold;
        return this;
    }

    public CircuitBreakerRuleBuilder withSlowCallRateThreshold(Double slowCallRateThreshold) {
        this.slowCallRateThreshold = slowCallRateThreshold;
        return this;
    }

    public CircuitBreakerRuleBuilder withSlowCallDurationThreshold(Long slowCallDurationThreshold) {
        this.slowCallDurationThreshold = slowCallDurationThreshold;
        return this;
    }

    public CircuitBreakerRuleBuilder withWaitDurationInOpenState(Long waitDurationInOpenState) {
        this.waitDurationInOpenState = waitDurationInOpenState;
        return this;
    }

    public CircuitBreakerRuleBuilder withPermittedNumberOfCallsInHalfOpenState(Integer permittedNumberOfCallsInHalfOpenState) {
        this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        return this;
    }

    public CircuitBreakerRuleBuilder withMinimumNumberOfCalls(Integer minimumNumberOfCalls) {
        this.minimumNumberOfCalls = minimumNumberOfCalls;
        return this;
    }

    public CircuitBreakerRuleBuilder withSlidingWindowSize(Integer slidingWindowSize) {
        this.slidingWindowSize = slidingWindowSize;
        return this;
    }

    public CircuitBreakerRuleBuilder withStatus(Integer status) {
        this.status = status;
        return this;
    }

    public CircuitBreakerRule build() {
        return CircuitBreakerRule.builder()
                .routeId(routeId)
                .failureRateThreshold(failureRateThreshold)
                .slowCallRateThreshold(slowCallRateThreshold)
                .slowCallDurationThreshold(slowCallDurationThreshold)
                .waitDurationInOpenState(waitDurationInOpenState)
                .permittedNumberOfCallsInHalfOpenState(permittedNumberOfCallsInHalfOpenState)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .slidingWindowSize(slidingWindowSize)
                .status(status)
                .build();
    }
}
