package com.delivery.tracker.async;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 异步任务事件
 * 用于通过Spring事件机制广播任务状态变化
 */
@Getter
public class AsyncTaskEvent extends ApplicationEvent {

    private final String taskId;
    private final AsyncTaskContext context;
    private final EventType eventType;
    private final Object payload;

    public enum EventType {
        STARTED,
        COMPLETED,
        FAILED,
        CANCELLED,
        TIMEOUT,
        PROGRESS
    }

    public AsyncTaskEvent(Object source, String taskId, AsyncTaskContext context, EventType eventType) {
        this(source, taskId, context, eventType, null);
    }

    public AsyncTaskEvent(Object source, String taskId, AsyncTaskContext context, EventType eventType, Object payload) {
        super(source);
        this.taskId = taskId;
        this.context = context;
        this.eventType = eventType;
        this.payload = payload;
    }
}
