package com.taskflow.core.task.api;

import com.taskflow.core.task.domain.ExecutionContext;
import com.taskflow.core.task.domain.TaskResult;

/**
 * 任务生命周期监听器 - 最小化接口
 * 用于监听任务执行的各个阶段
 */
public interface TaskLifecycleListener {

    /**
     * 任务开始执行
     */
    default void onStart(ExecutionContext context) {}

    /**
     * 任务执行成功
     */
    default void onSuccess(ExecutionContext context, TaskResult result) {}

    /**
     * 任务执行失败
     */
    default void onFailure(ExecutionContext context, Throwable error) {}

    /**
     * 任务执行完成（无论成功失败）
     */
    default void onComplete(ExecutionContext context) {}
}
