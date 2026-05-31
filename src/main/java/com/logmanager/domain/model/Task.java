package com.logmanager.domain.model;

import com.logmanager.common.enums.TaskStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class Task extends BaseEntity {
    private String taskId;
    private String name;
    private String type;
    private TaskStatus status;
    private Map<String, Object> parameters = new HashMap<>();
    private String scheduledBy;
    private Instant scheduledAt;
    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;
    private String result;
    private String errorMessage;
    private Integer retryCount;
    private Integer maxRetries;
}
