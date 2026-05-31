package com.datastandard.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.Map;

@Getter
public class TaskCompletedEvent extends ApplicationEvent {

    private final String taskId;
    private final String taskType;
    private final Instant completedAt;
    private final Map<String, Object> result;
    private final String traceId;

    public TaskCompletedEvent(Object source, String taskId, String taskType,
                              Map<String, Object> result, String traceId) {
        super(source);
        this.taskId = taskId;
        this.taskType = taskType;
        this.completedAt = Instant.now();
        this.result = result;
        this.traceId = traceId;
    }
}
