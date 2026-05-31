package com.llmgateway.inference.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.io.Serializable;

@Data
public class InferenceDTO implements Serializable {

    @NotBlank(message = "模型ID不能为空")
    private String modelId;

    @NotBlank(message = "Prompt不能为空")
    private String prompt;

    private Integer maxTokens = 1024;
    private Double temperature = 0.7;
    private Double topP = 0.9;
    private String provider;
    private Boolean stream = false;
}
