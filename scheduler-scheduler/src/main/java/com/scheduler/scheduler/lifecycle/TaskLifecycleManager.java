package com.scheduler.scheduler.lifecycle;

import com.scheduler.common.event.EventPublisher;
import com.scheduler.core.service.TaskExecutorService;
import com.scheduler.data.repository.ScheduledTaskRepository;
import com.scheduler.persistence.entity.ScheduledTask;
import com.scheduler.scheduler.cache.TaskCacheService;
import com.scheduler.scheduler.core.QuartzTaskScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaskLifecycleManager {

    private final ScheduledTaskRepository taskRepository;
    private final QuartzTaskScheduler taskScheduler;
    private final TaskExecutorService taskExecutorService;
    private final EventPublisher eventPublisher;
    private final TaskCacheService taskCacheService;

    public ScheduledTask createTask(ScheduledTask task) {
        ScheduledTask saved = taskRepository.create(task);

        if ("ACTIVE".equals(task.getStatus())) {
            taskScheduler.scheduleTask(saved);
        }

        taskCacheService.put(saved.getTaskId(), saved);

        eventPublisher.publish(new com.scheduler.common.event.BaseEvent(this, "task.created")
                .payload("taskId", saved.getTaskId())
                .payload("name", saved.getName()));

        return saved;
    }

    public ScheduledTask updateTask(String taskId, ScheduledTask task) {
        ScheduledTask existing = getTask(taskId);
        taskScheduler.unscheduleTask(existing);

        task.setTaskId(taskId);
        ScheduledTask updated = taskRepository.update(task);

        if ("ACTIVE".equals(updated.getStatus())) {
            taskScheduler.scheduleTask(updated);
        }

        taskCacheService.invalidate(taskId);
        taskCacheService.put(taskId, updated);

        return updated;
    }

    public void deleteTask(String taskId) {
        ScheduledTask task = getTask(taskId);
        taskScheduler.unscheduleTask(task);
        taskRepository.delete(taskId);
        taskCacheService.invalidate(taskId);
    }

    public void pauseTask(String taskId) {
        ScheduledTask task = getTask(taskId);
        taskScheduler.unscheduleTask(task);
        task.setStatus("PAUSED");
        taskRepository.update(task);
        taskCacheService.invalidate(taskId);
        taskCacheService.put(taskId, task);
        log.info("Paused task: {}", taskId);
    }

    public void resumeTask(String taskId) {
        ScheduledTask task = getTask(taskId);
        task.setStatus("ACTIVE");
        taskRepository.update(task);
        taskScheduler.scheduleTask(task);
        taskCacheService.invalidate(taskId);
        taskCacheService.put(taskId, task);
        log.info("Resumed task: {}", taskId);
    }

    public void triggerTask(String taskId, Map<String, Object> context) {
        log.info("Manually triggering task: {}", taskId);
        taskExecutorService.executeTask(taskId, context)
                .subscribe(
                        execution -> log.info("Task {} executed successfully, runId: {}", taskId, execution.getRunId()),
                        error -> log.error("Task {} execution failed", taskId, error)
                );
    }

    public ScheduledTask getTask(String taskId) {
        return taskCacheService.get(taskId, taskRepository::findById);
    }
}
