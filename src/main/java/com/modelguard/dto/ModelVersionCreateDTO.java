package com.modelguard.dto;

import lombok.Data;
import java.util.Map;

@Data
public class ModelVersionCreateDTO {

    private String modelId;

    private String version;

    private String parentVersion;

    private String description;

    private Map<String, Object> metrics;

    private Map<String, Object> artifacts;

    private Map<String, Object> trainingData;

    private Map<String, Object> hyperparameters;

    private String algorithm;

    private String framework;

    private String frameworkVersion;

    private String createdBy;

    private String checksum;

    private Long modelSizeBytes;

    private Map<String, Object> environment;

    private Map<String, Object> dependencies;

    private String notes;
}
