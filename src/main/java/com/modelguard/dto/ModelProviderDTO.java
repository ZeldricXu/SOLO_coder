package com.modelguard.dto;

import lombok.Data;
import java.util.Map;

@Data
public class ModelProviderDTO {

    private String providerId;

    private String providerName;

    private String providerType;

    private String baseUrl;

    private String apiKey;

    private Map<String, Object> config;

    private Integer weight;

    private Integer priority;

    private Integer timeoutMs;

    private Integer maxRetries;

    private Map<String, Object> supportedModels;

    private String healthCheckEndpoint;

    private String description;
}
