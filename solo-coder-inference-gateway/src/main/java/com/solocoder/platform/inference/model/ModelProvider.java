package com.solocoder.platform.inference.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelProvider implements Serializable {

    private static final long serialVersionUID = 1L;

    private String providerId;
    private String name;
    private String endpoint;
    private String apiKey;
    private List<String> supportedModels;
    private ProviderStatus status;
    private int weight;
    private int priority;
    private Map<String, String> config;

    public enum ProviderStatus {
        ACTIVE, DEGRADED, OFFLINE
    }
}
