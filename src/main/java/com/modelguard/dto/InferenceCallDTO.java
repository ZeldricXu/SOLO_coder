package com.modelguard.dto;

import lombok.Data;
import java.util.Map;

@Data
public class InferenceCallDTO {

    private String modelName;

    private String routeId;

    private Map<String, Object> requestBody;

    private String userId;

    private String traceId;

    private Boolean enableFallback;

    private Integer timeoutMs;
}
