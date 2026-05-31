package com.logmanager.domain.model;

import com.logmanager.common.enums.TaskStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
public class RunInstance extends BaseEntity {
    private String runId;
    private String entityId;
    private String phase;
    private TaskStatus status;
    private Double progress;
    private Instant startedAt;
    private Instant completedAt;
    private String errorDetail;
}
