package com.solocoder.platform.inference.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InferenceRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String requestId;
    private String modelType;
    private String prompt;
    private Map<String, Object> parameters;
    private Map<String, String> headers;
    private int timeoutMs;
}
