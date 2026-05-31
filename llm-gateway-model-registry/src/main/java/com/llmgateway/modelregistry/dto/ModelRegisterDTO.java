package com.llmgateway.modelregistry.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.io.Serializable;
import java.util.Map;

@Data
public class ModelRegisterDTO implements Serializable {

    @NotBlank(message = "模型名称不能为空")
    private String modelName;

    @NotBlank(message = "模型类型不能为空")
    private String modelType;

    @NotBlank(message = "提供商不能为空")
    private String provider;

    private String description;
    private String taskType;
    private String baseModel;
    private String license;
    private Map<String, String> tags;
    private String owner;
}
