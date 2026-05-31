package com.modelguard.dto;

import lombok.Data;
import java.util.Map;

@Data
public class ModelRegistrationDTO {

    private String modelId;

    private String modelName;

    private String modelType;

    private String description;

    private String owner;

    private String department;

    private Map<String, Object> metadata;

    private Map<String, Object> tags;

    private String license;

    private String repository;

    private String documentationUrl;
}
