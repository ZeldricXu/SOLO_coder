package com.scheduler.scheduler.service;

import com.scheduler.persistence.entity.ScheduledTask;
import com.scheduler.scheduler.health.SchedulerHealthChecker;
import com.scheduler.scheduler.lifecycle.TaskLifecycleManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleManagerService {

    private final TaskLifecycleManager lifecycleManager;
    private final SchedulerHealthChecker healthChecker;

    public ScheduledTask createTask(ScheduledTask task) {
        return lifecycleManager.createTask(task);
    }

    public ScheduledTask updateTask(String taskId, ScheduledTask task) {
        return lifecycleManager.updateTask(taskId, task);
    }

    public void deleteTask(String taskId) {
        lifecycleManager.deleteTask(taskId);
    }

    public void pauseTask(String taskId) {
        lifecycleManager.pauseTask(taskId);
    }

    public void resumeTask(String taskId) {
        lifecycleManager.resumeTask(taskId);
    }

    public void triggerTask(String taskId, Map<String, Object> context) {
        lifecycleManager.triggerTask(taskId, context);
    }

    public ScheduledTask getTask(String taskId) {
        return lifecycleManager.getTask(taskId);
    }

    public Set<String> getScheduledTasks() {
        return healthChecker.getUpcomingExecutions(Integer.MAX_VALUE).stream()
                .map(e -> (String) e.get("taskId"))
                .collect(java.util.stream.Collectors.toSet());
    }

    public Mono<List<Map<String, Object>>> getUpcomingExecutions(int limit) {
        return Mono.fromCallable(() -> healthChecker.getUpcomingExecutions(limit));
    }
}
