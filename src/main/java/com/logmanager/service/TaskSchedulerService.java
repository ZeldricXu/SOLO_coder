package com.logmanager.service;

import com.logmanager.common.enums.TaskStatus;
import com.logmanager.domain.model.Task;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

public interface TaskSchedulerService {
    Mono<Task> scheduleTask(String name, String type, Map<String, Object> parameters, String scheduledBy, Instant scheduledAt);
    Mono<Task> executeTask(String taskId);
    Mono<Task> getTask(String taskId);
    Flux<Task> getTasksByStatus(TaskStatus status);
    Flux<Task> getTasksByType(String type);
    Mono<Task> updateTaskProgress(String taskId, double progress);
    Mono<Task> completeTask(String taskId, String result);
    Mono<Task> failTask(String taskId, String errorMessage);
    Mono<Task> cancelTask(String taskId);
    Mono<Long> getTaskCountByStatus(TaskStatus status);
    void registerTaskHandler(String type, Function<Map<String, Object>, Mono<String>> handler);
}
