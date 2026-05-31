package com.device.platform.dto;

import lombok.Data;
import java.time.Instant;

@Data
public class RunStatusResponse {
    private String runId;
    private String entityId;
    private String phase;
    private String status;
    private Double progress;
    private Instant startedAt;
    private Instant completedAt;
    private String errorDetail;
}
