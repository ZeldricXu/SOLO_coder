package com.enterprise.risk.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfig implements Serializable {

    @JsonProperty("model_id")
    private String modelId;

    @JsonProperty("model_name")
    private String modelName;

    @JsonProperty("model_version")
    private String modelVersion;

    @JsonProperty("model_path")
    private String modelPath;

    @JsonProperty("feature_names")
    private List<String> featureNames;

    @JsonProperty("feature_extractors")
    private Map<String, String> featureExtractors;

    @JsonProperty("default_values")
    private Map<String, Object> defaultValues;

    @JsonProperty("input_name")
    private String inputName;

    @JsonProperty("output_name")
    private String outputName;

    @JsonProperty("output_shape")
    private long[] outputShape;

    @JsonProperty("threshold")
    @Builder.Default
    private Double threshold = 0.5;

    @JsonProperty("enabled")
    @Builder.Default
    private Boolean enabled = true;

    @JsonProperty("created_at")
    @Builder.Default
    private Long createdAt = Instant.now().toEpochMilli();

    @JsonProperty("updated_at")
    @Builder.Default
    private Long updatedAt = Instant.now().toEpochMilli();

    @JsonProperty("weight")
    @Builder.Default
    private Double weight = 0.5;
}
