package com.llmgateway.featurestore.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.io.Serializable;
import java.util.Map;

@Data
public class FeatureRegisterDTO implements Serializable {

    @NotBlank(message = "特征名称不能为空")
    private String featureName;

    @NotBlank(message = "特征类型不能为空")
    private String featureType;

    private String description;

    @NotBlank(message = "所属实体不能为空")
    private String entity;

    @NotBlank(message = "值类型不能为空")
    private String valueType;

    private Integer ttl = 86400;

    private Map<String, String> tags;

    private String owner;
}
