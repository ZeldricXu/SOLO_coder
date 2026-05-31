package com.taskflow.core.task.api;

import com.taskflow.core.task.domain.Task;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务调度器 - 最小化接口
 * 仅定义任务调度相关的核心操作
 */
public interface TaskScheduler {

    /**
     * 调度任务
     * @param task 任务定义
     * @return 调度后的任务
     */
    Mono<Task> schedule(Task task);

    /**
     * 取消任务调度
     * @param tenantId 租户ID
     * @param taskId 任务ID
     * @return 是否取消成功
     */
    Mono<Boolean> unschedule(String tenantId, String taskId);

    /**
     * 获取需要执行的任务
     * @param tenantId 租户ID
     * @param time 当前时间
     * @return 需要执行的任务列表
     */
    Mono<List<Task>> getTasksToRun(String tenantId, LocalDateTime time);

    /**
     * 更新任务下次执行时间
     * @param taskId 任务ID
     * @param lastRunTime 上次执行时间
     * @param nextRunTime 下次执行时间
     */
    void updateNextRunTime(String taskId, LocalDateTime lastRunTime, LocalDateTime nextRunTime);
}
