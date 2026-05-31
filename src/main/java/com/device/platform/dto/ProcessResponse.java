package com.device.platform.dto;

import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
public class ProcessResponse {
    private String runId;
    private String entityId;
    private String status;
    private Map<String, Object> result;
    private String traceId;
    private Instant createdAt;
    private Long durationMs;
}
