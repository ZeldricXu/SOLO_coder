package com.delivery.tracker.async;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 全局异步任务事件监听器
 * 演示如何通过Spring事件机制接收任务状态通知
 */
@Slf4j
@Component
public class AsyncTaskEventListener {

    @Async
    @EventListener
    public void handleTaskEvent(AsyncTaskEvent event) {
        switch (event.getEventType()) {
            case STARTED:
                log.info("[事件] 任务开始: taskId={}", event.getTaskId());
                break;
            case COMPLETED:
                log.info("[事件] 任务完成: taskId={}, 耗时={}ms",
                        event.getTaskId(), event.getContext().getElapsedMs());
                break;
            case FAILED:
                log.error("[事件] 任务失败: taskId={}, error={}",
                        event.getTaskId(), event.getContext().getErrorMessage());
                break;
            case CANCELLED:
                log.warn("[事件] 任务取消: taskId={}", event.getTaskId());
                break;
            case TIMEOUT:
                log.warn("[事件] 任务超时: taskId={}", event.getTaskId());
                break;
            case PROGRESS:
                log.debug("[事件] 任务进度: taskId={}, payload={}",
                        event.getTaskId(), event.getPayload());
                break;
        }
    }
}
