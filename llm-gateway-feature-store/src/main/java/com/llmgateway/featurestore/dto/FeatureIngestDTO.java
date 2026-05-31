package com.llmgateway.featurestore.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class FeatureIngestDTO implements Serializable {

    @NotBlank(message = "特征ID不能为空")
    private String featureId;

    @NotBlank(message = "实体键不能为空")
    private String entityKey;

    @NotEmpty(message = "特征值不能为空")
    private Map<String, Object> value;

    private Long timestampMs;

    private String source;
}
