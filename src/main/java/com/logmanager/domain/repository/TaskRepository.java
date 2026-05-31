package com.logmanager.domain.repository;

import com.logmanager.domain.model.Task;
import com.logmanager.common.enums.TaskStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;

public interface TaskRepository {
    Mono<Task> save(Task task);
    Mono<Task> findById(String taskId);
    Flux<Task> findByStatus(TaskStatus status);
    Flux<Task> findByType(String type);
    Flux<Task> findByScheduledTimeRange(Instant start, Instant end);
    Mono<Long> countByStatus(TaskStatus status);
}
