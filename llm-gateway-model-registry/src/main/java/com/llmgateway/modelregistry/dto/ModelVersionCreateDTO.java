package com.llmgateway.modelregistry.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.io.Serializable;
import java.util.Map;

@Data
public class ModelVersionCreateDTO implements Serializable {

    @NotBlank(message = "模型ID不能为空")
    private String modelId;

    @NotBlank(message = "版本号不能为空")
    private String version;

    private String description;
    private String artifactPath;
    private Map<String, Object> metrics;
    private Map<String, Object> parameters;
    private String dataset;
    private String commitHash;
    private String createdBy;
}
