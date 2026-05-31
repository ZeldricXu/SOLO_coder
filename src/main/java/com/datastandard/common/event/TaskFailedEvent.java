package com.datastandard.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;

@Getter
public class TaskFailedEvent extends ApplicationEvent {

    private final String taskId;
    private final String taskType;
    private final Instant failedAt;
    private final String errorMessage;
    private final Throwable exception;
    private final String traceId;

    public TaskFailedEvent(Object source, String taskId, String taskType,
                           String errorMessage, Throwable exception, String traceId) {
        super(source);
        this.taskId = taskId;
        this.taskType = taskType;
        this.failedAt = Instant.now();
        this.errorMessage = errorMessage;
        this.exception = exception;
        this.traceId = traceId;
    }
}
