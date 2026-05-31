package com.taskflow.core.task.api;

import com.taskflow.core.task.domain.*;
import reactor.core.publisher.Mono;

/**
 * 任务执行器 - 最小化接口
 * 仅定义任务执行相关的核心操作
 */
public interface TaskExecutor {

    /**
     * 提交任务执行
     * @param request 任务执行请求
     * @return 任务执行结果
     */
    Mono<TaskResult> execute(TaskRequest request);

    /**
     * 获取任务执行状态
     * @param tenantId 租户ID
     * @param runId 运行实例ID
     * @return 任务执行结果
     */
    Mono<TaskResult> getStatus(String tenantId, String runId);

    /**
     * 取消任务执行
     * @param tenantId 租户ID
     * @param runId 运行实例ID
     * @return 是否取消成功
     */
    Mono<Boolean> cancel(String tenantId, String runId);
}
