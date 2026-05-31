package com.modelguard.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class ModelRouteDTO {

    private String routeId;

    private String routeName;

    private String modelName;

    private List<String> primaryProviders;

    private List<String> fallbackProviders;

    private String loadBalanceStrategy;

    private Boolean enableFallback;

    private Integer timeoutMs;

    private Integer maxRetries;

    private Double failureThreshold;

    private Integer circuitBreakerOpenMs;

    private Map<String, Object> routingRules;

    private String description;
}
