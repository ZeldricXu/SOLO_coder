package com.llmgateway.inference.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class InferenceResponse implements Serializable {
    private String requestId;
    private String modelId;
    private String provider;
    private String responseText;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Long latencyMs;
    private Boolean fallbackUsed;
    private String fallbackReason;
}
