package com.datastandard.modules.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CircuitBreakerConfig {

    private String name;

    @Builder.Default
    private int failureThreshold = 50;

    @Builder.Default
    private int slowCallThreshold = 100;

    @Builder.Default
    private Duration slowCallDuration = Duration.ofSeconds(2);

    @Builder.Default
    private Duration waitDurationInOpenState = Duration.ofSeconds(30);

    @Builder.Default
    private int permittedNumberOfCallsInHalfOpenState = 10;

    @Builder.Default
    private int slidingWindowSize = 100;

    @Builder.Default
    private int minimumNumberOfCalls = 20;

    @Builder.Default
    private Duration maxWaitDuration = Duration.ofMillis(500);
}
