package com.solocoder.dns.common.entity;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class RunInstance implements Serializable {
    private String runId;
    private String entityId;
    private String phase;
    private Double progress;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorDetail;
}
