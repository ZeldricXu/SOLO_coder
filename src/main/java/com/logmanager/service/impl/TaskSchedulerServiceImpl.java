package com.logmanager.service.impl;

import com.logmanager.common.enums.TaskStatus;
import com.logmanager.domain.event.DomainEvent;
import com.logmanager.domain.event.EventPublisher;
import com.logmanager.domain.model.Task;
import com.logmanager.domain.repository.TaskRepository;
import com.logmanager.service.TaskSchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSchedulerServiceImpl implements TaskSchedulerService {

    private final TaskRepository taskRepository;
    private final EventPublisher eventPublisher;

    private final Map<String, Function<Map<String, Object>, Mono<String>>> taskHandlers = new ConcurrentHashMap<>();

    @Override
    public Mono<Task> scheduleTask(String name, String type, Map<String, Object> parameters, String scheduledBy, Instant scheduledAt) {
        Task task = new Task();
        task.setId(UUID.randomUUID().toString());
        task.setTaskId(UUID.randomUUID().toString());
        task.setName(name);
        task.setType(type);
        task.setStatus(TaskStatus.PENDING);
        task.setParameters(parameters);
        task.setScheduledBy(scheduledBy);
        task.setScheduledAt(scheduledAt);
        task.setRetryCount(0);
        task.setMaxRetries(3);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());

        return taskRepository.save(task)
                .doOnSuccess(saved -> {
                    log.info("Task scheduled: {} [{}] for {}", name, type, scheduledAt);
                    eventPublisher.publish(new DomainEvent("task.scheduled", saved.getTaskId(), "task"));
                });
    }

    @Override
    public Mono<Task> executeTask(String taskId) {
        return taskRepository.findById(taskId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Task not found: " + taskId)))
                .flatMap(task -> {
                    task.setStatus(TaskStatus.RUNNING);
                    task.setStartedAt(Instant.now());
                    task.setUpdatedAt(Instant.now());
                    return taskRepository.save(task);
                })
                .flatMap(task -> {
                    Function<Map<String, Object>, Mono<String>> handler = taskHandlers.get(task.getType());
                    if (handler == null) {
                        return failTask(taskId, "No handler registered for task type: " + task.getType());
                    }
                    return handler.apply(task.getParameters())
                            .flatMap(result -> completeTask(taskId, result))
                            .onErrorResume(e -> failTask(taskId, e.getMessage()));
                })
                .doOnSuccess(executed -> {
                    log.info("Task executed: {} with status: {}", taskId, executed.getStatus());
                    eventPublisher.publish(new DomainEvent("task.executed", taskId, "task"));
                });
    }

    @Override
    public Mono<Task> getTask(String taskId) {
        return taskRepository.findById(taskId);
    }

    @Override
    public Flux<Task> getTasksByStatus(TaskStatus status) {
        return taskRepository.findByStatus(status);
    }

    @Override
    public Flux<Task> getTasksByType(String type) {
        return taskRepository.findByType(type);
    }

    @Override
    public Mono<Task> updateTaskProgress(String taskId, double progress) {
        return taskRepository.findById(taskId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Task not found: " + taskId)))
                .flatMap(task -> {
                    task.setProgress(progress);
                    task.setUpdatedAt(Instant.now());
                    return taskRepository.save(task);
                });
    }

    @Override
    public Mono<Task> completeTask(String taskId, String result) {
        return taskRepository.findById(taskId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Task not found: " + taskId)))
                .flatMap(task -> {
                    task.setStatus(TaskStatus.COMPLETED);
                    task.setResult(result);
                    task.setCompletedAt(Instant.now());
                    task.setDurationMs(Instant.now().toEpochMilli() - task.getStartedAt().toEpochMilli());
                    task.setUpdatedAt(Instant.now());
                    return taskRepository.save(task);
                })
                .doOnSuccess(t -> eventPublisher.publish(new DomainEvent("task.completed", taskId, "task")));
    }

    @Override
    public Mono<Task> failTask(String taskId, String errorMessage) {
        return taskRepository.findById(taskId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Task not found: " + taskId)))
                .flatMap(task -> {
                    task.setStatus(TaskStatus.FAILED);
                    task.setErrorMessage(errorMessage);
                    task.setCompletedAt(Instant.now());
                    if (task.getStartedAt() != null) {
                        task.setDurationMs(Instant.now().toEpochMilli() - task.getStartedAt().toEpochMilli());
                    }
                    task.setUpdatedAt(Instant.now());
                    return taskRepository.save(task);
                })
                .doOnSuccess(t -> eventPublisher.publish(new DomainEvent("task.failed", taskId, "task")));
    }

    @Override
    public Mono<Task> cancelTask(String taskId) {
        return taskRepository.findById(taskId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Task not found: " + taskId)))
                .flatMap(task -> {
                    if (task.getStatus().isTerminal()) {
                        return Mono.just(task);
                    }
                    task.setStatus(TaskStatus.CANCELLED);
                    task.setCompletedAt(Instant.now());
                    task.setUpdatedAt(Instant.now());
                    return taskRepository.save(task);
                })
                .doOnSuccess(t -> eventPublisher.publish(new DomainEvent("task.cancelled", taskId, "task")));
    }

    @Override
    public Mono<Long> getTaskCountByStatus(TaskStatus status) {
        return taskRepository.countByStatus(status);
    }

    @Override
    public void registerTaskHandler(String type, Function<Map<String, Object>, Mono<String>> handler) {
        taskHandlers.put(type, handler);
        log.info("Registered task handler for type: {}", type);
    }
}
