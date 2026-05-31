package com.solocoder.platform.inference.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InferenceResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private String requestId;
    private String providerId;
    private String modelId;
    private String content;
    private long latencyMs;
    private int tokenCount;
    private InferenceStatus status;
    private String errorMessage;
    private Map<String, Object> metadata;
    private LocalDateTime timestamp;

    public enum InferenceStatus {
        SUCCESS, TIMEOUT, RATE_LIMITED, MODEL_ERROR, FALLBACK_USED
    }
}
