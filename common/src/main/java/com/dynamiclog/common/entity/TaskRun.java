package com.dynamiclog.common.entity;

import com.dynamiclog.common.enums.TaskStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class TaskRun extends BaseEntity {
    private String runId;
    private String taskId;
    private TaskStatus status;
    private Double progress;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long durationMs;
    private String result;
    private String errorDetail;
    private String workerId;
    private String traceId;
}
